package com.jmsbrain.mailcal

import java.io.IOException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import org.w3c.dom.Node

data class CalInfo(
    val url: String,
    val name: String,
    val ctag: String?,
    val readOnly: Boolean,
)

/** One calendar file on the server. */
data class Resource(val href: String, val etag: String?, val ics: String)

class DavError(message: String, val status: Int = 0) : Exception(message)

/** Just enough CalDAV (RFC 4791) to list calendars and read and write events. */
class CalDav(user: String, password: String) {

    private val auth = Credentials.basic(user, password, Charsets.UTF_8)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", auth).build()) }
        .build()

    private val xml = "application/xml; charset=utf-8".toMediaType()
    private val ics = "text/calendar; charset=utf-8".toMediaType()

    // ---- discovery ----

    /** Finds the calendar home behind [server] (e.g. https://dav.mailbox.org/). */
    fun findHome(server: String): String {
        val base = server.trim().let { if ("://" in it) it else "https://$it" }.toHttpUrl()
        val candidates = listOf(base, base.resolve("/.well-known/caldav")!!, base.resolve("/caldav/")!!, base.resolve("/")!!).distinct()
        var lastError: Exception? = null
        for (url in candidates) {
            try {
                val principal = propfind(url, 0, PRINCIPAL_BODY).firstNotNullOfOrNull { r -> r.hrefIn("current-user-principal")?.let { r.base.resolve(it) } }
                    ?: continue
                val home = propfind(principal, 0, HOME_BODY).firstNotNullOfOrNull { r -> r.hrefIn("calendar-home-set")?.let { r.base.resolve(it) } }
                    ?: continue
                return home.toString()
            } catch (e: DavError) {
                if (e.status == 401) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw when (lastError) {
            is DavError -> lastError
            is IOException -> DavError(networkMessage(lastError, base.host))
            else -> DavError("${base.host} doesn't look like a CalDAV server.")
        }
    }

    fun calendars(home: String): List<CalInfo> {
        val homeUrl = home.toHttpUrl()
        return propfind(homeUrl, 1, CALENDARS_BODY).mapNotNull { r ->
            val type = r.el("resourcetype") ?: return@mapNotNull null
            if (type.child("calendar") == null) return@mapNotNull null
            val comps = r.el("supported-calendar-component-set")
            if (comps != null && comps.children("comp").none { it.getAttribute("name").equals("VEVENT", true) }) return@mapNotNull null
            val privs = r.el("current-user-privilege-set")
            val writable = privs == null || privs.children("privilege").any { p ->
                p.child("write") != null || p.child("write-content") != null || p.child("all") != null
            }
            val url = r.url.toString()
            CalInfo(
                url = url,
                name = r.el("displayname")?.textContent?.trim().orEmpty().ifBlank { r.url.pathSegments.lastOrNull { it.isNotBlank() } ?: "Calendar" },
                ctag = r.el("getctag")?.textContent?.trim(),
                readOnly = !writable,
            )
        }
    }

    // ---- events ----

    fun events(calendar: String, from: ZonedDateTime, to: ZonedDateTime): List<Resource> {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val body = QUERY_BODY
            .replace("{from}", from.withZoneSameInstant(ZoneOffset.UTC).format(fmt))
            .replace("{to}", to.withZoneSameInstant(ZoneOffset.UTC).format(fmt))
        return report(calendar.toHttpUrl(), body).mapNotNull { r ->
            val data = r.el("calendar-data")?.textContent ?: return@mapNotNull null
            Resource(r.url.toString(), r.el("getetag")?.textContent?.trim(), data)
        }
    }

    fun get(url: String): Resource {
        val req = Request.Builder().url(url).get().build()
        return call(req) { res -> Resource(url, res.header("ETag"), res.body.string()) }
    }

    /** Creates ([etag] null) or replaces an event. Returns the new ETag if the server sent one. */
    fun put(url: String, text: String, etag: String?): String? {
        val req = Request.Builder().url(url).put(text.toRequestBody(ics)).apply {
            if (etag != null) header("If-Match", etag) else header("If-None-Match", "*")
        }.build()
        return call(req) { it.header("ETag") }
    }

    fun delete(url: String, etag: String?) {
        val req = Request.Builder().url(url).delete().apply { if (etag != null) header("If-Match", etag) }.build()
        call(req) { }
    }

    // ---- plumbing ----

    private fun propfind(url: HttpUrl, depth: Int, body: String) = multistatus(
        Request.Builder().url(url).method("PROPFIND", body.toRequestBody(xml)).header("Depth", depth.toString()).build(),
    )

    private fun report(url: HttpUrl, body: String) = multistatus(
        Request.Builder().url(url).method("REPORT", body.toRequestBody(xml)).header("Depth", "1").build(),
    )

    private fun <T> call(req: Request, read: (Response) -> T): T {
        val res = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw DavError(networkMessage(e, req.url.host))
        }
        res.use {
            if (!it.isSuccessful) throw statusError(it.code, req.url.host)
            return read(it)
        }
    }

    private fun multistatus(req: Request): List<DavResponse> = call(req) { res ->
        val text = res.body.string()
        val doc = try {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(text.byteInputStream())
        } catch (e: Exception) {
            throw DavError("${req.url.host} sent an answer this app can't read.")
        }
        val base = res.request.url
        doc.documentElement.children("response").mapNotNull { r ->
            val href = r.child("href")?.textContent?.trim() ?: return@mapNotNull null
            val url = base.resolve(href) ?: return@mapNotNull null
            // Only props from 200 propstats.
            val props = r.children("propstat").filter { ps ->
                ps.child("status")?.textContent?.contains(" 200") != false
            }.mapNotNull { it.child("prop") }
            DavResponse(base, url, props)
        }
    }

    private fun statusError(code: Int, host: String) = when (code) {
        401 -> DavError("$host didn't accept that email and password. If you use two-factor sign-in, make an app password in mailbox.org settings.", code)
        403 -> DavError("$host says this account can't do that.", code)
        404 -> DavError("$host couldn't find that. It may have been deleted elsewhere.", code)
        412 -> DavError("This event changed somewhere else. It's been refreshed; try again.", code)
        else -> DavError("$host said $code.", code)
    }

    private fun networkMessage(e: IOException, host: String) = when (e) {
        is javax.net.ssl.SSLException -> "The phone doesn't trust the security certificate of $host."
        is java.net.UnknownHostException -> "Couldn't reach $host. Check that the phone is online."
        is java.net.SocketTimeoutException -> "$host took too long to answer."
        else -> "Couldn't connect to $host."
    }

    private class DavResponse(val base: HttpUrl, val url: HttpUrl, val props: List<Element>) {
        fun el(local: String): Element? = props.firstNotNullOfOrNull { it.child(local) }
        fun hrefIn(local: String): String? = el(local)?.child("href")?.textContent?.trim()
    }

    companion object {
        private const val PRINCIPAL_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>"""

        private const val HOME_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>"""

        private const val CALENDARS_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/">
<d:prop><d:resourcetype/><d:displayname/><cs:getctag/><d:current-user-privilege-set/><c:supported-calendar-component-set/></d:prop>
</d:propfind>"""

        private const val QUERY_BODY = """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
<d:prop><d:getetag/><c:calendar-data/></d:prop>
<c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">
<c:time-range start="{from}" end="{to}"/>
</c:comp-filter></c:comp-filter></c:filter>
</c:calendar-query>"""
    }
}

private fun Element.children(local: String): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n.nodeType == Node.ELEMENT_NODE && (n.localName ?: n.nodeName.substringAfter(':')) == local) out += n as Element
    }
    return out
}

private fun Element.child(local: String): Element? = children(local).firstOrNull()
