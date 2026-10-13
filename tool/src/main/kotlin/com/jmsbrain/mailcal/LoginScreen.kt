package com.jmsbrain.mailcal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    var email by rememberOnPage("email") { mutableStateOf("") }
    var password by rememberOnPage("password") { mutableStateOf("") }
    var server by rememberOnPage("server") { mutableStateOf(Repo.DEFAULT_SERVER) }
    var busy by rememberOnPage("busy") { mutableStateOf(false) }
    var error by rememberOnPage("error") { mutableStateOf<String?>(null) }
    val scope = LocalPageScope.current

    fun signIn() {
        if (email.isBlank() || password.isEmpty()) {
            error = "Enter your email and password."
            return
        }
        busy = true
        error = null
        scope.launch {
            runCatching { Repo.signIn(server, email, password) }
                .onSuccess { password = "" }  // This page stays around for sign-out.
                .onFailure { error = it.message ?: "Couldn't sign in." }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = designDp(24f), vertical = designDp(32f)),
    ) {
        BasicText("Mailcal", style = headingStyle())
        Gap(12f)
        BasicText(
            "Sign in with your mailbox.org email and password. If you use two-factor sign-in, use an app password instead.",
            style = paragraphStyle(),
        )
        Gap(16f)
        Field("EMAIL", email, { email = it.trim(); error = null }, "you@mailbox.org", caps = false)
        Field("PASSWORD", password, { password = it; error = null }, password = true)
        Field("SERVER", server, { server = it.trim(); error = null }, caps = false)
        Gap(20f)
        if (busy) Note("Signing in…") else TextButton("SIGN IN") { signIn() }
        error?.let {
            Gap(12f)
            BasicText(it, style = paragraphStyle())
        }
        Gap(24f)
        Note("Your password stays on this phone, encrypted, and is only sent to the server above.")
    }
}
