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

    /** 威海校园网内直连的教务主机（只有 http，无 TLS）。 */
    private val weihaiDirectHosts = setOf("jwts.hitwh.edu.cn", "yjsgl.hitwh.edu.cn")

    private val approvedHosts = mapOf(
        EASToken.Campus.BENBU to (benbuIdsGatewayHosts + setOf("ids.hit.edu.cn")),
        // 威海有两条入口：WebVPN 反代，以及校园网内直连的教务主机。
        // 直连时登录表单直接由 jwts 自己渲染，不走 CAS，因此主机也要在凭据白名单里。
        EASToken.Campus.WEIHAI to setOf(
            "webvpn.hitwh.edu.cn",
            "jwts.hitwh.edu.cn",
            "yjsgl.hitwh.edu.cn",
            "ids.hit.edu.cn",
        ),
        // 深圳统一身份认证 CAS：aTrust 放行后由 jw 代理 302 到这里，是三校区里
        // 唯一还要再输一次账号密码的页面，因此同样需要自动填充。
        EASToken.Campus.SHENZHEN to setOf("ids-hit-edu-cn-s.hitsz.edu.cn")
    )

    fun matchesLoginPage(campus: EASToken.Campus, url: String, isMainFrame: Boolean): Boolean {
        if (!isMainFrame) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path?.lowercase() ?: return false
        return matchesLoginOrigin(campus, uri) && isLoginFormPath(campus, path)
    }

    /**
     * 登录表单页的路径判据。
     *
     * 统一身份认证（CAS）用 `/authserver/login`；威海教务直连由教务系统自己渲染表单，
     * 路径是 `/xtgl/login_slogin.html`。两套都要能自动填充，否则直连时用户得手输。
     */
    private fun isLoginFormPath(campus: EASToken.Campus, path: String): Boolean {
        val trimmed = path.trimEnd('/')
        if (trimmed.endsWith("/authserver/login")) return true
        return campus == EASToken.Campus.WEIHAI && (
            trimmed.contains("/xtgl/login") ||
                trimmed.contains("login_slogin")
            )
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
          // A delayed autofill must not replace an account/password already entered by the user.
          if ((user.value && user.value !== username) || (pass.value && pass.value !== password)) return false;
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
            return element && !element.disabled &&
              !!(element.offsetWidth || element.offsetHeight || element.getClientRects().length);
          }
          function labelOf(element) {
            return element ? String(element.innerText || element.textContent ||
              element.getAttribute('aria-label') || '').replace(/\s+/g, '') : '';
          }
          function isRememberLabel(text) {
            // CAS 免登录与 MFA 信任设备是两种选择，不能替用户确认后者。
            if (/信任|设备|trust|device/i.test(text)) return false;
            return /免登录|记住我|保持登录|rememberme|keepmesignedin/i.test(text);
          }
          function ensureChecked(control, readChecked, action) {
            var checked = readChecked();
            // 重试只等待控件出现，不反复点击异步控件，也不覆盖用户随后取消的选择。
            if (control.getAttribute('data-hita-remember-handled') !== 'true') {
              control.setAttribute('data-hita-remember-handled', 'true');
              if (!checked) control.click();
            }
            return JSON.stringify({checked: readChecked(), action: action});
          }
          var boxes = document.querySelectorAll('input[type="checkbox"]');
          for (var i = 0; i < boxes.length; i++) {
            var box = boxes[i];
            if (box.disabled) continue;
            var labels = Array.from(box.labels || []);
            if (!visible(box) && !labels.some(visible)) continue;
            var text = labels.map(labelOf).join('') + labelOf(box) +
              String(box.name || '') + String(box.id || '');
            if (isRememberLabel(text)) {
              return ensureChecked(box, function() { return box.checked; }, 'checkbox');
            }
          }
          // 仅处理能够读取真实状态的自定义复选框；未知状态的 label 不盲目点击。
          var controls = document.querySelectorAll('[role="checkbox"][aria-checked]');
          for (var j = 0; j < controls.length; j++) {
            var control = controls[j];
            if (!visible(control) || control.getAttribute('aria-disabled') === 'true') continue;
            var state = control.getAttribute('aria-checked');
            if (state !== 'true' && state !== 'false') continue;
            if (isRememberLabel(labelOf(control))) {
              return ensureChecked(control, function() {
                return control.getAttribute('aria-checked') === 'true';
              }, 'aria-checkbox');
            }
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
            // 威海教务直连只有 http（校内网，无 TLS）：http://jwts.hitwh.edu.cn。
            // 走 WebVPN 时仍是 https，两种都要放行。
            campus == EASToken.Campus.WEIHAI && host in weihaiDirectHosts -> scheme == "http" || scheme == "https"
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
