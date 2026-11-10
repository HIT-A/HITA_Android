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
