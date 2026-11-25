package cn.limpu.hita.data.source.web.grader

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 登录 WebView 的导航与凭据注入规则。
 *
 * 两个入口共用同一套 CAS 表单结构，但主体不同：直连只有 `grader.hitsz.edu.cn` 与
 * `ids.hit.edu.cn`；校外走 aTrust 网关时会出现网关代理域、信任门户与统一认证镜像域。
 * 所有判定都按入口取值，默认 [GraderSessionPolicy.Entry.DIRECT]，即校园网行为不变。
 */
internal object GraderCasLoginPolicy {
    const val CAS_ORIGIN = "https://ids.hit.edu.cn"

    /** 深圳 aTrust 网关提供的统一认证镜像域。 */
    const val CAS_GATEWAY_ORIGIN = "https://ids-hit-edu-cn-s.hitsz.edu.cn"

    private const val CAS_HOST = "ids.hit.edu.cn"
    private const val CAS_GATEWAY_HOST = "ids-hit-edu-cn-s.hitsz.edu.cn"
    private const val LOGIN_PATH = "/authserver/login"
    private const val SERVICE_PATH = "/authserver/oauth2.0/callbackAuthorize"
    private const val ACCOUNT_TYPE = "userNameLogin"
    const val BRIDGE_NAME = "hitaGraderForm"
    private const val GRADER_CLIENT_ID = "1463851627464626176"
    private const val HITSZ_DOMAIN = "hitsz.edu.cn"
    private val loginQueryKeys = setOf("service", "type")
    private val serviceQueryKeys = setOf("client_id", "redirect_uri", "response_type", "state", "client_name")

    /** 该入口允许出现统一认证页面的 origin 集合；桥消息只接受这些来源。 */
    fun casOrigins(entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT): Set<String> =
        when (entry) {
            GraderSessionPolicy.Entry.DIRECT -> setOf(CAS_ORIGIN)
            GraderSessionPolicy.Entry.GATEWAY -> setOf(CAS_ORIGIN, CAS_GATEWAY_ORIGIN)
        }

    fun callback(entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT): String =
        "${entry.origin}/api/auth/callback"

    /**
     * 该地址是不是本入口的 OAuth 回调。
     *
     * 不能按字面量比对：aTrust 网关改写出来的地址一律带 `:443`（它自己的 verify 与
     * `appUrl` 都是这种形态），所以端口把 443 与缺省视为等价。其余仍然精确匹配——
     * scheme、host、path 逐项校验，且不得附带 query 或 fragment。
     */
    fun isEntryCallback(entry: GraderSessionPolicy.Entry, url: String): Boolean {
        val uri = uriOrNull(url) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return false
        if (!uri.host.orEmpty().equals(entry.host, ignoreCase = true)) return false
        if (uri.port != -1 && uri.port != 443) return false
        return uri.rawPath == "/api/auth/callback" && uri.rawQuery == null && uri.rawFragment == null
    }

    fun mayNavigate(url: String, entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT): Boolean {
        val uri = uriOrNull(url) ?: return false
        if (entry == GraderSessionPolicy.Entry.DIRECT) {
            return uri.isHttpsHost(entry.host) || uri.isHttpsHost(CAS_HOST)
        }
        if (uri.scheme != "https" || uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        // 校外链路依次经过 grader 网关域、aTrust 门户（trust.hitsz.edu.cn）与统一认证镜像。
        return host == entry.host || host == HITSZ_DOMAIN || host.endsWith(".$HITSZ_DOMAIN") ||
            host == CAS_HOST
    }

    fun isLoginEndpoint(url: String, entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT): Boolean =
        uriOrNull(url)?.let { it.isHttpsCasHost(entry) && it.path == LOGIN_PATH } == true

    fun loginService(url: String, entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT): String? {
        val service = loginQuery(url, entry)?.get("service") ?: return null
        val uri = uriOrNull(service) ?: return null
        if (!uri.isHttpsCasHost(entry) || uri.rawPath != SERVICE_PATH || uri.rawFragment != null) return null
        val query = uri.queryValues() ?: return null
        return service.takeIf {
            query.keys == serviceQueryKeys && query["client_id"] == GRADER_CLIENT_ID &&
                query["redirect_uri"]?.let { redirect -> isEntryCallback(entry, redirect) } == true &&
                query["response_type"] == "code" &&
                query["client_name"] == "CasOAuthClient" && !query["state"].isNullOrBlank()
        }
    }

    fun matchesFormAction(
        pageUrl: String,
        actionUrl: String,
        entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT,
    ): Boolean {
        val service = loginService(pageUrl, entry) ?: return false
        return loginQuery(actionUrl, entry)?.get("service") == service
    }

    fun accountTabUrl(
        pageUrl: String,
        entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT,
    ): String? {
        if (loginQuery(pageUrl, entry)?.containsKey("type") != false) return null
        val service = loginService(pageUrl, entry) ?: return null
        val uri = uriOrNull(pageUrl) ?: return null
        val origin = "${uri.scheme}://${uri.rawAuthority}"
        return "$origin$LOGIN_PATH?type=$ACCOUNT_TYPE&service=${URLEncoder.encode(service, "UTF-8")}"
    }

    /** Reserve before evaluation; the script never retries a click or supplies a verification factor. */
    fun fillScript(
        service: String,
        username: String,
        password: String,
        proofToken: String?,
        entry: GraderSessionPolicy.Entry = GraderSessionPolicy.Entry.DIRECT,
    ): String {
        val origins = casOrigins(entry).joinToString(", ", "[", "]") { jsString(it) }
        return """
        (function (service, username, password, proofToken, casOrigins) {
          function loginUrl(rawUrl) {
            var url = new URL(rawUrl);
            if (casOrigins.indexOf(url.origin) < 0 || url.username || url.password || url.hash ||
                url.pathname !== '$LOGIN_PATH') return null;
            var types = url.searchParams.getAll('type');
            var services = url.searchParams.getAll('service');
            if (types.length > 1 || (types.length === 1 && types[0] !== '$ACCOUNT_TYPE') ||
                services.length !== 1 || services[0] !== service) return null;
            for (var key of url.searchParams.keys()) {
              if (key !== 'service' && key !== 'type') return null;
            }
            return url;
          }
          if (self !== top || !loginUrl(location.href)) return 'unsupported';
          function visible(element) {
            if (!element || !element.getClientRects().length) return false;
            var style = window.getComputedStyle(element);
            return style.visibility === 'visible' && style.display !== 'none';
          }
          function usable(element) {
            return visible(element) && !element.disabled && !element.readOnly &&
              !element.hasAttribute('disabled') && element.getAttribute('aria-disabled') !== 'true';
          }
          var forms = Array.from(document.querySelectorAll('form#loginFromId')).filter(visible);
          if (forms.length !== 1) {
            var hasAccountTab = Array.from(document.querySelectorAll('a[href]')).some(function (link) {
              if (!visible(link)) return false;
              var url = loginUrl(link.href);
              return url && url.searchParams.get('type') === '$ACCOUNT_TYPE';
            });
            return hasAccountTab ? 'accountTab' : 'unsupported';
          }
          var form = forms[0];
          function approvedForm() {
            return form.isConnected && form.method === 'post' && loginUrl(location.href) && loginUrl(form.action) &&
              (!form.target || form.target.toLowerCase() === '_self');
          }
          var user = form.querySelector('input#username[name="username"][type="text"]');
          var pass = form.querySelector('input#password[type="password"][name="userPassword"], input#password[type="password"][name="passwordText"]');
          if (!approvedForm() || !usable(user) || !usable(pass) || user.form !== form || pass.form !== form ||
              (user.maxLength >= 0 && username.length > user.maxLength) ||
              (pass.maxLength >= 0 && password.length > pass.maxLength) ||
              (user.value && user.value !== username) || (pass.value && pass.value !== password)) return 'unsupported';
          var button = form.querySelector('#login_submit');
          var activated = false;
          function sameCredentials() { return user.value === username && pass.value === password; }
          function report(same, action) {
            var bridge = window.$BRIDGE_NAME;
            if (proofToken && bridge) bridge.postMessage(proofToken + (same ? ':matched:' + action : ':changed'));
          }
          if (proofToken && typeof form.submit === 'function') {
            // The official button and slider completion use the site's jQuery submit path; observe
            // the actual native submit, not the click, which can stop at a CAPTCHA or validation.
            var submit = form.submit;
            form.submit = function () {
              var same = this === form && approvedForm() && sameCredentials();
              var action = form.action;
              var result = submit.apply(this, arguments);
              if (this === form) report(same, action);
              return result;
            };
          }
          var setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
          setValue.call(user, username);
          setValue.call(pass, password);
          [user, pass].forEach(function (input) {
            input.dispatchEvent(new Event('input', {bubbles: true}));
            input.dispatchEvent(new Event('change', {bubbles: true}));
          });
          function challengeVisible() {
            return visible(form.querySelector('input#captcha')) ||
              visible(document.getElementById('sliderCaptchaDiv'));
          }
          var jq = window.jQuery;
          if (!usable(button) || challengeVisible() || window.needCaptcha === true ||
              window.needCaptcha === 'true' || !jq) return 'filled';
          button.addEventListener('click', function () { activated = true; }, true);
          function onPreflight(event, xhr, settings) {
            var check = new URL(settings.url, location.href);
            if (casOrigins.indexOf(check.origin) < 0 || check.pathname !== '/authserver/checkNeedCaptcha.htl' ||
                check.searchParams.get('username') !== username) return;
            jq(document).off('ajaxComplete', onPreflight);
            if (!activated && xhr.status === 200 && window.needCaptcha === false && !challengeVisible() &&
                approvedForm() && sameCredentials() && usable(button) &&
                !button.hasAttribute('formaction') && !button.hasAttribute('formtarget')) {
              activated = true;
              button.click();
            }
          }
          // Exercise the site's usual username-blur preflight before clicking. On uncertainty,
          // leave the official page visible and let the user operate it.
          jq(document).on('ajaxComplete', onPreflight);
          setTimeout(function () { jq(document).off('ajaxComplete', onPreflight); }, 5000);
          jq(user).trigger('focusout');
          return 'filled';
        })(${jsString(service)}, ${jsString(username)}, ${jsString(password)}, ${proofToken?.let(::jsString) ?: "null"}, $origins);
        """.trimIndent()
    }

    private fun loginQuery(
        url: String,
        entry: GraderSessionPolicy.Entry,
    ): Map<String, String>? {
        val uri = uriOrNull(url) ?: return null
        if (!uri.isHttpsCasHost(entry) || uri.rawPath != LOGIN_PATH || uri.rawFragment != null) return null
        val query = uri.queryValues() ?: return null
        if (query.keys.any { it !in loginQueryKeys } || query["type"]?.let { it != ACCOUNT_TYPE } == true) return null
        return query
    }

    /** 直连只认 `ids.hit.edu.cn`；网关入口额外接受统一认证镜像域。 */
    private fun URI.isHttpsCasHost(entry: GraderSessionPolicy.Entry): Boolean {
        if (scheme != "https" || rawUserInfo != null) return false
        val hostname = host ?: return false
        val approved = when (entry) {
            GraderSessionPolicy.Entry.DIRECT -> hostname == CAS_HOST
            GraderSessionPolicy.Entry.GATEWAY ->
                hostname == CAS_HOST || hostname == CAS_GATEWAY_HOST
        }
        return approved && (rawAuthority == hostname || rawAuthority == "$hostname:443")
    }

    private fun URI.isHttpsHost(hostname: String): Boolean =
        scheme == "https" && host == hostname && (rawAuthority == hostname || rawAuthority == "$hostname:443")

    private fun uriOrNull(url: String): URI? = runCatching { URI(url) }.getOrNull()

    private fun URI.queryValues(): Map<String, String>? {
        val raw = rawQuery ?: return null
        val result = mutableMapOf<String, String>()
        for (entry in raw.split('&')) {
            val split = entry.indexOf('=')
            if (split <= 0) return null
            val key = decode(entry.substring(0, split)) ?: return null
            val value = decode(entry.substring(split + 1)) ?: return null
            if (result.put(key, value) != null) return null
        }
        return result
    }

    private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()

    private fun jsString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (char in value) when {
            char == '"' || char == '\\' -> append('\\').append(char)
            char < ' ' || char == '\u2028' || char == '\u2029' || char.isSurrogate() ->
                append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> append(char)
        }
        append('"')
    }
}
