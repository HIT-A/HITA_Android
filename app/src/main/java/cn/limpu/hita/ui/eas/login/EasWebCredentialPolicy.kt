package cn.limpu.hita.ui.eas.login

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.source.preference.EasCredential
import java.net.URI

internal object EasWebCredentialPolicy {
    /** 本部 CAS 网关主机：2026-10 起网关域名由 ivpn.hit.edu.cn 改为 vpn.hit.edu.cn，旧域名保留兼容。 */
    private val benbuIdsGatewayHosts = setOf(
        "ids-hit-edu-cn-s.vpn.hit.edu.cn",
        "ids-hit-edu-cn-s.ivpn.hit.edu.cn"
    )

    private val approvedHosts = mapOf(
        EASToken.Campus.BENBU to (benbuIdsGatewayHosts + setOf("ids.hit.edu.cn")),
        EASToken.Campus.WEIHAI to setOf("webvpn.hitwh.edu.cn", "ids.hit.edu.cn")
    )

    fun matchesLoginPage(campus: EASToken.Campus, url: String, isMainFrame: Boolean): Boolean {
        if (!isMainFrame || campus == EASToken.Campus.SHENZHEN) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path?.lowercase() ?: return false
        return matchesLoginOrigin(campus, uri) && path.trimEnd('/').endsWith("/authserver/login")
    }

    fun matchesLoginOrigin(campus: EASToken.Campus, origin: String): Boolean {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        return uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
            matchesLoginOrigin(campus, uri)
    }

    fun readOptionalCredential(read: () -> EasCredential?): EasCredential? =
        runCatching(read).getOrNull()

    fun autofillScript(username: String, password: String): String {
        val usernameJson = username.toJavaScriptString()
        val passwordJson = password.toJavaScriptString()
        return """
            (function() {
              var username = $usernameJson;
              var password = $passwordJson;
              function firstVisible(selector) {
                var fields = document.querySelectorAll(selector);
                for (var i = 0; i < fields.length; i++) {
                  var field = fields[i];
                  if (!field.disabled && (field.offsetWidth || field.offsetHeight || field.getClientRects().length)) return field;
                }
                return null;
              }
              var user = firstVisible('input[autocomplete="username"], input[name*="user" i], input[id*="user" i], input[type="text"]');
              var pass = firstVisible('input[autocomplete="current-password"], input[type="password"]');
              if (!user || !pass) return false;
              var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
              setter.call(user, username);
              setter.call(pass, password);
              [user, pass].forEach(function(input) {
                input.dispatchEvent(new Event('input', {bubbles:true}));
                input.dispatchEvent(new Event('change', {bubbles:true}));
              });
              username = '';
              password = '';
              return true;
            })();
        """.trimIndent()
    }

    fun captureScript(): String = """
        (function() {
          if (window.__hitaCredentialCaptureInstalled) return;
          window.__hitaCredentialCaptureInstalled = true;
          function firstVisible(selector) {
            var fields = document.querySelectorAll(selector);
            for (var i = 0; i < fields.length; i++) {
              var field = fields[i];
              if (!field.disabled && (field.offsetWidth || field.offsetHeight || field.getClientRects().length)) return field;
            }
            return null;
          }
          document.addEventListener('submit', function() {
            var user = firstVisible('input[autocomplete="username"], input[name*="user" i], input[id*="user" i], input[type="text"]');
            var pass = firstVisible('input[autocomplete="current-password"], input[type="password"]');
            if (!user || !pass || !user.value || !pass.value) return;
            if (window.hitaCredentialBridge) {
              window.hitaCredentialBridge.postMessage(JSON.stringify({username:user.value, password:pass.value}));
            }
          }, true);
        })();
    """.trimIndent()

    private fun String.toJavaScriptString(): String = buildString {
        append('"')
        this@toJavaScriptString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun matchesLoginOrigin(campus: EASToken.Campus, uri: URI): Boolean {
        if (uri.port != -1 || uri.rawUserInfo != null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val approvedScheme = when {
            campus == EASToken.Campus.BENBU && host in benbuIdsGatewayHosts -> scheme == "http"
            else -> scheme == "https"
        }
        return approvedScheme && host in approvedHosts[campus].orEmpty()
    }
}

internal class EasWebCredentialCaptureState {
    private var candidate: EasCredential? = null

    fun stage(credential: EasCredential) {
        candidate = credential
    }

    fun commitOnSuccess(save: (EasCredential) -> Unit) {
        val toSave = candidate ?: return
        candidate = null
        save(toSave)
    }

    fun discard() {
        candidate = null
    }
}
