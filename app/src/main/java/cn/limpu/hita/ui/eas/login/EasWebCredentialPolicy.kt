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
        EASToken.Campus.WEIHAI to setOf("webvpn.hitwh.edu.cn", "ids.hit.edu.cn"),
        // 深圳统一身份认证 CAS：aTrust 放行后由 jw 代理 302 到这里，是三校区里
        // 唯一还要再输一次账号密码的页面，因此同样需要自动填充。
        EASToken.Campus.SHENZHEN to setOf("ids-hit-edu-cn-s.hitsz.edu.cn")
    )

    fun matchesLoginPage(campus: EASToken.Campus, url: String, isMainFrame: Boolean): Boolean {
        if (!isMainFrame) return false
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

    /** 页面内公共片段：定位并填入用户名/密码输入框。 */
    private fun fillFormJs(): String = """
        function firstVisible(selector) {
          var fields = document.querySelectorAll(selector);
          for (var i = 0; i < fields.length; i++) {
            var field = fields[i];
            if (!field.disabled && (field.offsetWidth || field.offsetHeight || field.getClientRects().length)) return field;
          }
          return null;
        }
        function fillForms(username, password) {
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
          return true;
        }
    """.trimIndent()

    fun autofillScript(username: String, password: String): String {
        val usernameJson = username.toJavaScriptString()
        val passwordJson = password.toJavaScriptString()
        return """
            (function() {
              var username = $usernameJson;
              var password = $passwordJson;
              ${fillFormJs()}
              if (!fillForms(username, password)) return false;
              username = '';
              password = '';
              return true;
            })();
        """.trimIndent()
    }

    /**
     * 勾选 CAS 登录页的「N 天免登录」（rememberMe）。
     *
     * 该勾选项决定 CASTGC 是持久 cookie 还是会话 cookie：不勾选时登录拿到的
     * `CASTGC` 为 `is_persistent=0`，进程被杀后身份票据即失效、必须重新输密码；
     * 勾选后服务端下发带到期时间的持久票据（深圳实测 14 天），冷启动可复用身份层。
     * 因此登录前主动勾选，不依赖用户手动操作。
     */
    fun rememberMeScript(): String = """
        (function() {
          function visible(element) {
            if (!element || element.disabled) return false;
            return !!(element.offsetWidth || element.offsetHeight || element.getClientRects().length);
          }
          function labelOf(element) {
            return String(
              element.innerText || element.textContent || element.value ||
              element.getAttribute('aria-label') || element.getAttribute('title') || ''
            ).replace(/\s+/g, '');
          }
          function isRememberLabel(text) {
            if (!text) return false;
            if (text.indexOf('免登录') >= 0) return true;
            if (text.indexOf('记住我') >= 0) return true;
            return /^(\d+天)?.*(免登录|记住|保持不变)/.test(text) || text.toLowerCase().indexOf('remember') >= 0;
          }
          function fire(element, type) {
            element.dispatchEvent(new Event(type, {bubbles:true}));
          }
          function setChecked(input) {
            if (!input) return false;
            if (input.checked) return true;
            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'checked').set;
            if (setter) setter.call(input, true); else input.checked = true;
            fire(input, 'input');
            fire(input, 'change');
            fire(input, 'click');
            return !!input.checked;
          }

          var boxes = document.querySelectorAll('input[type="checkbox"]');
          for (var i = 0; i < boxes.length; i++) {
            var box = boxes[i];
            if (!visible(box)) continue;
            var wrapper = box.closest('label, li, div, span');
            var text = labelOf(wrapper && wrapper !== box ? wrapper : box) +
              ' ' + String(box.name || '') + ' ' + String(box.id || '');
            if (isRememberLabel(text.replace(/\s+/g, ''))) {
              return JSON.stringify({
                checked: setChecked(box),
                action: 'checkbox',
                label: text.trim().slice(0, 40)
              });
            }
          }

          var nodes = document.querySelectorAll('label,span,div,li,p,a');
          for (var j = 0; j < nodes.length; j++) {
            var node = nodes[j];
            if (!visible(node)) continue;
            var own = labelOf(node).replace(/\s+/g, '');
            if (!own || own.length > 12 || !isRememberLabel(own)) continue;
            var inner = node.querySelector ? node.querySelector('input[type="checkbox"]') : null;
            if (inner) {
              return JSON.stringify({checked: setChecked(inner), action: 'label-checkbox', label: own});
            }
            var clicked = node.closest('label') || node;
            clicked.click();
            return JSON.stringify({checked: true, action: 'label-click', label: own});
          }
          return JSON.stringify({checked: false, action: 'none'});
        })();
    """.trimIndent()

    /**
     * 自动填充并提交统一身份认证表单，让再次登录对用户无感。
     *
     * 只在填好后**一次**触发提交：先点可见的登录按钮（CAS 的 `#login_submit`、aTrust
     * 的 `.ix-button-primary`），没有按钮才回退到 `form.requestSubmit()`。返回 JSON，
     * 宿主据此判断是否还需用户手动操作。
     */
    fun autofillAndSubmitScript(username: String, password: String): String {
        val usernameJson = username.toJavaScriptString()
        val passwordJson = password.toJavaScriptString()
        return """
            (function() {
              var username = $usernameJson;
              var password = $passwordJson;
              ${fillFormJs()}
              function visible(element) {
                if (!element || element.disabled) return false;
                return !!(element.offsetWidth || element.offsetHeight || element.getClientRects().length);
              }
              if (!fillForms(username, password)) {
                return JSON.stringify({filled:false, submitted:false});
              }
              username = '';
              password = '';
              var candidates = document.querySelectorAll(
                '#login_submit, button[type=submit], input[type=submit], .ix-button-primary'
              );
              var button = null;
              for (var i = 0; i < candidates.length; i++) {
                if (visible(candidates[i])) { button = candidates[i]; break; }
              }
              if (button) {
                button.click();
                return JSON.stringify({filled:true, submitted:true, via:'button'});
              }
              var form = document.querySelector('form');
              if (form && form.requestSubmit) {
                form.requestSubmit();
                return JSON.stringify({filled:true, submitted:true, via:'form'});
              }
              return JSON.stringify({filled:true, submitted:false});
            })();
        """.trimIndent()
    }

    /**
     * 解析 [autofillAndSubmitScript] 的返回值，判断这次是否真的把表单提交出去了。
     *
     * WebView 的 `evaluateJavascript` 回调给的是 JSON 字面量字符串（形如
     * `"{\"filled\":true,\"submitted\":true}"`），也可能被转义成对象，这里统一按字符串处理。
     */
    fun submittedFromResult(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return false
        val json = text.removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"")
        return json.contains("\"submitted\":true") || json.contains("\"submitted\": true")
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
