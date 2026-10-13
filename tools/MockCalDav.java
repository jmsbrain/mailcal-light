// Fake CalDAV server for trying the app on the emulator: java tools/MockCalDav.java
// Then sign in with any email/password and server http://10.0.2.2:5232/
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MockCalDav {
    static final Map<String, Map<String, String[]>> cals = new LinkedHashMap<>(); // cal -> href -> {etag, ics}
    static int tag = 1;

    public static void main(String[] a) throws Exception {
        cals.put("/cal/personal/", new LinkedHashMap<>());
        cals.put("/cal/work/", new LinkedHashMap<>());
        cals.put("/cal/holidays/", new LinkedHashMap<>());
        LocalDate t = LocalDate.now();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMdd");
        add("/cal/personal/", "dentist", ev("dentist", "Dentist", "123 Main St", t.plusDays(1).format(f) + "T100000", t.plusDays(1).format(f) + "T110000", null));
        add("/cal/personal/", "yoga", ev("yoga", "Yoga", "Studio", t.format(f) + "T180000", t.format(f) + "T190000", "FREQ=WEEKLY"));
        add("/cal/work/", "standup", ev("standup", "Team standup", "", t.minusDays(3).format(f) + "T090000", t.minusDays(3).format(f) + "T091500", "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"));
        add("/cal/work/", "offsite", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:offsite\r\nSUMMARY:Offsite\r\nDTSTART;VALUE=DATE:" + t.plusDays(5).format(f) + "\r\nDTEND;VALUE=DATE:" + t.plusDays(7).format(f) + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");
        // Reminder goes off about a minute after the server starts.
        DateTimeFormatter z = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
        Instant soon = Instant.now().plusSeconds(180);
        add("/cal/personal/", "soon", String.join("\r\n", "BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:soon",
            "SUMMARY:Reminder test", "LOCATION:Kitchen", "DTSTART:" + z.format(soon), "DTEND:" + z.format(soon.plusSeconds(1800)),
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT2M", "END:VALARM", "END:VEVENT", "END:VCALENDAR", ""));
        add("/cal/holidays/", "h1", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:h1\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:" + t.plusDays(3).format(f) + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");

        HttpServer s = HttpServer.create(new InetSocketAddress(5232), 0);
        s.createContext("/", MockCalDav::handle);
        s.start();
        System.out.println("Mock CalDAV on :5232");
    }

    static String ev(String uid, String title, String loc, String start, String end, String rrule) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:" + uid + "\r\nSUMMARY:" + title + "\r\nLOCATION:" + loc
            + "\r\nDTSTART;TZID=America/New_York:" + start + "\r\nDTEND;TZID=America/New_York:" + end
            + (rrule != null ? "\r\nRRULE:" + rrule : "") + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    static void add(String cal, String name, String ics) {
        cals.get(cal).put(cal + name + ".ics", new String[]{"\"" + (tag++) + "\"", ics});
    }

    static synchronized void handle(HttpExchange x) throws IOException {
        String m = x.getRequestMethod(), p = x.getRequestURI().getPath();
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        System.out.println(m + " " + p + " " + Optional.ofNullable(x.getRequestHeaders().getFirst("If-Match")).orElse(""));
        String cal = cals.keySet().stream().filter(p::startsWith).findFirst().orElse(null);
        switch (m) {
            case "PROPFIND" -> {
                StringBuilder r = new StringBuilder();
                if (body.contains("current-user-principal")) r.append(resp(p, "<d:current-user-principal><d:href>/principals/me/</d:href></d:current-user-principal>"));
                else if (body.contains("calendar-home-set")) r.append(resp(p, "<c:calendar-home-set><d:href>/cal/</d:href></c:calendar-home-set>"));
                else {
                    r.append(resp("/cal/", "<d:resourcetype><d:collection/></d:resourcetype>"));
                    for (String c : cals.keySet()) {
                        boolean ro = c.contains("holidays");
                        String name = c.split("/")[2];
                        r.append(resp(c, "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>" + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                            + "</d:displayname><cs:getctag>" + cals.get(c).values().stream().map(v -> v[0]).reduce("", String::concat).hashCode() + "</cs:getctag>"
                            + "<d:current-user-privilege-set><d:privilege><d:read/></d:privilege>" + (ro ? "" : "<d:privilege><d:write/></d:privilege>") + "</d:current-user-privilege-set>"
                            + "<c:supported-calendar-component-set><c:comp name=\"VEVENT\"/></c:supported-calendar-component-set>"));
                    }
                }
                multi(x, r.toString());
            }
            case "REPORT" -> {
                StringBuilder r = new StringBuilder();
                for (var e : cals.get(cal).entrySet())
                    r.append(resp(e.getKey(), "<d:getetag>" + e.getValue()[0] + "</d:getetag><c:calendar-data>" + e.getValue()[1].replace("&", "&amp;").replace("<", "&lt;") + "</c:calendar-data>"));
                multi(x, r.toString());
            }
            case "PUT" -> {
                String[] cur = cals.get(cal).get(p);
                String im = x.getRequestHeaders().getFirst("If-Match");
                if (im != null && (cur == null || !cur[0].equals(im))) { send(x, 412, ""); return; }
                if (x.getRequestHeaders().getFirst("If-None-Match") != null && cur != null) { send(x, 412, ""); return; }
                String et = "\"" + (tag++) + "\"";
                cals.get(cal).put(p, new String[]{et, body});
                System.out.println(body);
                x.getResponseHeaders().add("ETag", et);
                send(x, cur == null ? 201 : 204, "");
            }
            case "DELETE" -> {
                String[] cur = cals.get(cal).remove(p);
                send(x, cur == null ? 404 : 204, "");
            }
            default -> send(x, 405, "");
        }
    }

    static String resp(String href, String props) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>" + props + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    static void multi(HttpExchange x, String inner) throws IOException {
        x.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
        send(x, 207, "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\">" + inner + "</d:multistatus>");
    }

    static void send(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream o = x.getResponseBody()) { o.write(b); }
        x.close();
    }
}
