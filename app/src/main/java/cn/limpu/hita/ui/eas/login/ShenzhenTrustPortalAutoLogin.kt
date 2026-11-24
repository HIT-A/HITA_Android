package cn.limpu.hita.ui.eas.login

/**
 * 深圳 aTrust 门户登录页（`trust.hitsz.edu.cn/portal/#/login`）的自动推进规则。
 *
 * 该页只有一个「统一身份认证」入口：勾选《用户协议》后点一次登录按钮才会跳到
 * `ids-hit-edu-cn-s.hitsz.edu.cn/authserver/login`。没有这一步，深职账号在
 * WebView 里必须手点一次才能到 CAS，做不到"无感登录"。
 */
internal object ShenzhenTrustPortalAutoLogin {

    /** 只有 aTrust 门户的登录页需要这一跳；门户主界面直接续跳教务入口。 */
    fun shouldAutoLogin(url: String): Boolean =
        WebLoginSuccessPolicy.isShenzhenTrustPortalAuthPage(url) && isLoginRoute(url)

    /**
     * 网关把未认证的请求送到了 aTrust 的客户端探测页 `shortcut.html`。
     *
     * 该页默认探测本机是否装了深信服客户端，WebView 里没有这个客户端，于是停住不前进。
     * 判断只看 host 与路径，不看 query——签名令牌和回跳地址都在 query 里，重载要保持原样。
     */
    fun shouldForceWebMode(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        return uri.scheme.orEmpty().equals("https", ignoreCase = true) &&
            uri.host.orEmpty().equals("trust.hitsz.edu.cn", ignoreCase = true) &&
            uri.path.orEmpty().equals("/portal/shortcut.html", ignoreCase = true)
    }

    /**
     * 置 `sessionStorage.forceWeb`，让 shortcut 页跳过客户端探测、改走网页认证。
     *
     * aTrust 的网页客户端在探测 localhost 之前会读这个标志；新版页面把会话数据按 SDP
     * 地址加了 `[n]` 前缀，而 shortcut.html 仍读旧的、无前缀的键，所以两个键都要写。
     * 与 `WebViewLoginActivity.applyShenzhenForceWebMode` 的行为一致。
     */
    fun buildForceWebScript(): String = """
        (function() {
          try {
            sessionStorage.setItem('forceWeb', 'true');
            var mapKey = '__Prefix_map__';
            var prefixMap = JSON.parse(sessionStorage.getItem(mapKey) || '[]');
            if (!Array.isArray(prefixMap)) prefixMap = [];
            var origin = window.location.origin;
            var entry = prefixMap.find(function(item) { return item && item.addr === origin; });
            if (!entry) {
              var maxPrefix = prefixMap.reduce(function(max, item) {
                var match = item && String(item.pre || '').match(/^\[(\d+)\]$/);
                return match ? Math.max(max, Number(match[1])) : max;
              }, 0);
              entry = {pre: '[' + (maxPrefix + 1) + ']', addr: origin};
              prefixMap.unshift(entry);
            }
            sessionStorage.setItem(mapKey, JSON.stringify(prefixMap));
            sessionStorage.setItem(entry.pre + 'forceWeb', 'true');
            return true;
          } catch (e) {
            return false;
          }
        })();
    """.trimIndent()

    private fun isLoginRoute(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val route = uri.rawFragment.orEmpty().lowercase().removePrefix("!")
        return route.startsWith("/login")
    }

    /**
     * 勾选协议并点击「登录」，返回 JSON 供宿主记录：
     * `{clicked:true, agreement:true|false, via:'button'}`。
     *
     * 只在按钮可见时点击，避免点到尚未渲染的骨架屏。
     */
    fun buildLoginScript(): String = """
        (function() {
          function visible(element) {
            if (!element || element.disabled) return false;
            var rect = element.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0;
          }
          function labelOf(element) {
            return String(element.innerText || element.textContent || '').replace(/\s+/g, '').trim();
          }
          var agreement = false;
          function boxLabel(box) {
            var own = box.getAttribute('aria-label') || box.getAttribute('name') || '';
            var parent = box.closest('label');
            var labelText = parent ? (parent.innerText || parent.textContent || '') : '';
            return (own + ' ' + labelText).replace(/\s+/g, '');
          }
          function isAgreementLabel(text) {
            return text.indexOf('协议') >= 0 || text.indexOf('同意') >= 0 ||
              text.toLowerCase().indexOf('agree') >= 0;
          }
          /**
           * 派发完整的指针/鼠标事件序列。aTrust 门户的按钮与复选框都绑在
           * pointerdown/pointerup 上，单发 click 不会推进 SPA 状态。
           */
          function tap(element) {
            var rect = element.getBoundingClientRect();
            var x = rect.left + rect.width / 2;
            var y = rect.top + rect.height / 2;
            var base = {
              bubbles: true, cancelable: true, composed: true,
              clientX: x, clientY: y, screenX: x, screenY: y,
              button: 0, buttons: 1, pointerId: 1, pointerType: 'mouse', isPrimary: true
            };
            try {
              element.dispatchEvent(new PointerEvent('pointerdown', base));
              element.dispatchEvent(new MouseEvent('mousedown', base));
              element.dispatchEvent(new PointerEvent('pointerup', base));
              element.dispatchEvent(new MouseEvent('mouseup', base));
            } catch (error) {
              // 老 WebView 没有 PointerEvent 构造器时至少补一次 click。
            }
            element.click();
          }
          var boxes = document.querySelectorAll('input[type="checkbox"]');
          var target = null;
          for (var i = 0; i < boxes.length; i++) {
            var candidate = boxes[i];
            if (!visible(candidate) && !candidate.closest('label')) continue;
            if (isAgreementLabel(boxLabel(candidate))) { target = candidate; break; }
            // 只有一个勾选框且没有可识别文案时，才退回到它（例如纯图标协议框）。
            if (boxes.length === 1) target = candidate;
          }
          if (target) {
            if (!target.checked) {
              var setter = Object.getOwnPropertyDescriptor(
                window.HTMLInputElement.prototype, 'checked'
              ).set;
              if (setter) setter.call(target, true); else target.checked = true;
              target.dispatchEvent(new Event('input', {bubbles:true}));
              target.dispatchEvent(new Event('change', {bubbles:true}));
              tap(target);
            }
            agreement = !!target.checked;
          }
          var buttons = document.querySelectorAll(
            '.ix-button-primary, button, [role="button"]'
          );
          for (var j = 0; j < buttons.length; j++) {
            var button = buttons[j];
            if (!visible(button) || button.disabled) continue;
            var text = labelOf(button);
            if (text.indexOf('登录') < 0 && text.toLowerCase().indexOf('login') < 0) continue;
            // aTrust 门户是 SPA，按钮挂着 ix-button 与禁用态样式类，只认真实的
            // 指针事件序列：直接 button.click() 实测停在 #/login 不前进。
            tap(button);
            return JSON.stringify({clicked:true, agreement:agreement, via:'button'});
          }
          return JSON.stringify({clicked:false, agreement:agreement});
        })();
    """.trimIndent()
}
