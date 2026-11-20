package cn.limpu.hita.data.source.web.grader

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** The only CAS form that can receive native Grader credentials. Other auth pages are user-operated. */
internal object GraderCasLoginPolicy {
    const val CAS_ORIGIN = "https://ids.hit.edu.cn"
    private const val CAS_HOST = "ids.hit.edu.cn"
    private const val GRADER_HOST = "grader.hitsz.edu.cn"
    private const val LOGIN_PATH = "/authserver/login"
    private const val SERVICE_PATH = "/authserver/oauth2.0/callbackAuthorize"
    private const val CALLBACK = "${GraderSessionPolicy.ORIGIN}/api/auth/callback"
    private const val ACCOUNT_TYPE = "userNameLogin"
    const val BRIDGE_NAME = "hitaGraderForm"
    private const val GRADER_CLIENT_ID = "1463851627464626176"
    private val loginQueryKeys = setOf("service", "type")
    private val serviceQueryKeys = setOf("client_id", "redirect_uri", "response_type", "state", "client_name")

    fun mayNavigate(url: String): Boolean {
        val uri = uriOrNull(url) ?: return false
        return uri.isHttpsHost(GRADER_HOST) || uri.isHttpsHost(CAS_HOST)
    }

    fun isLoginEndpoint(url: String): Boolean = uriOrNull(url)?.let {
        it.isHttpsHost(CAS_HOST) && it.path == LOGIN_PATH
    } == true

    fun loginService(url: String): String? {
        val service = loginQuery(url)?.get("service") ?: return null
        val uri = uriOrNull(service) ?: return null
        if (!uri.isHttpsHost(CAS_HOST) || uri.rawPath != SERVICE_PATH || uri.rawFragment != null) return null
        val query = uri.queryValues() ?: return null
        return service.takeIf {
            query.keys == serviceQueryKeys && query["client_id"] == GRADER_CLIENT_ID &&
                query["redirect_uri"] == CALLBACK && query["response_type"] == "code" &&
                query["client_name"] == "CasOAuthClient" && !query["state"].isNullOrBlank()
        }
    }

    fun matchesFormAction(pageUrl: String, actionUrl: String): Boolean {
        val service = loginService(pageUrl) ?: return false
        return loginQuery(actionUrl)?.get("service") == service
    }

    fun accountTabUrl(pageUrl: String): String? {
        if (loginQuery(pageUrl)?.containsKey("type") != false) return null
        val service = loginService(pageUrl) ?: return null
        return "$CAS_ORIGIN$LOGIN_PATH?type=$ACCOUNT_TYPE&service=${URLEncoder.encode(service, "UTF-8")}"
    }

    /** Reserve before evaluation; the script never retries a click or supplies a verification factor. */
    fun fillScript(service: String, username: String, password: String, proofToken: String?): String = """
        (function (service, username, password, proofToken) {
          function loginUrl(rawUrl) {
            var url = new URL(rawUrl);
            if (url.origin !== '$CAS_ORIGIN' || url.username || url.password || url.hash ||
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
            if (check.origin !== '$CAS_ORIGIN' || check.pathname !== '/authserver/checkNeedCaptcha.htl' ||
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
        })(${jsString(service)}, ${jsString(username)}, ${jsString(password)}, ${proofToken?.let(::jsString) ?: "null"});
    """.trimIndent()

    private fun loginQuery(url: String): Map<String, String>? {
        val uri = uriOrNull(url) ?: return null
        if (!uri.isHttpsHost(CAS_HOST) || uri.rawPath != LOGIN_PATH || uri.rawFragment != null) return null
        val query = uri.queryValues() ?: return null
        if (query.keys.any { it !in loginQueryKeys } || query["type"]?.let { it != ACCOUNT_TYPE } == true) return null
        return query
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
