(function () {
  'use strict';

  console.log('[SMS-Inbox] Plugin initialized');

  // State
  let messages = [];
  let isLoading = false;
  let autoRefreshTimer = null;
  let loggedInState = false;

  // Extract OTP helper
  function detectOtp(body) {
    if (!body) return null;
    const isOtpContext = /(otp|kode|code|verifikas|pin|password|rahasia|token)/i.test(body);
    if (!isOtpContext) return null;

    // Pattern 1: keyword followed closely by digits
    const m1 = body.match(/(?:otp|kode|code|verifikasi|pin|password)[^0-9a-zA-Z]{1,15}([0-9]{4,8})\b/i);
    if (m1) return m1[1];

    // Pattern 2: standalone 4 to 8 digit number
    const m2 = body.match(/\b([0-9]{4,8})\b/);
    if (m2) return m2[1];

    return null;
  }

  // HTML-escape helper (prevents stored XSS via sender/date/body fields)
  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  // Detect whether the user is logged into the modem Web UI
  function isUserLoggedIn() {
    // 1. Check modem-specific session in sessionStorage (Wowi / Qualcomm CPE standard)
    try {
      const s = sessionStorage.getItem('session');
      if (s && s !== '""' && s.trim().length > 0) return true;
    } catch (_) {}

    // 2. Check common session/token keys
    const authKeys = ['session', 'sessionId', 'token', 'user', 'isLogin', 'auth', 'auth_token'];
    for (const key of authKeys) {
      try {
        const val = sessionStorage.getItem(key) || localStorage.getItem(key);
        if (val && val !== '""' && val !== 'false' && val !== '0' && val.trim().length > 0) {
          return true;
        }
      } catch (_) {}
    }

    // 3. Check document cookies
    if (document.cookie) {
      const cookies = document.cookie.split(';');
      for (const c of cookies) {
        const [k, v] = c.trim().split('=');
        if (/session|token|auth|user/i.test(k) && v && v.length > 2) {
          return true;
        }
      }
    }

    // 4. URL path/hash checks: If currently on a login route, definitely not logged in
    const fullRoute = (window.location.pathname + window.location.hash).toLowerCase();
    if (fullRoute.includes('/login') || fullRoute.includes('/d/login')) {
      return false;
    }

    // 5. DOM checks: presence of active password input
    const pwdInput = document.querySelector('input[type="password"]');
    if (pwdInput && pwdInput.offsetParent !== null) {
      return false;
    }

    // 6. DOM checks: presence of dashboard navigation or logout control
    const hasLogout = Array.from(document.querySelectorAll('button, a, span, .el-dropdown-item')).some(el =>
      /logout|log\s*out|sign\s*out|keluar|退出/i.test(el.textContent || '')
    );
    if (hasLogout) return true;

    const hasNav = !!document.querySelector('.el-tabs__header, .el-menu, nav, .navbar');
    if (hasNav && !pwdInput) return true;

    return false;
  }

  // Fetch messages from proxy API
  async function fetchMessages(sync = false) {
    isLoading = true;
    updateRefreshBtnState();
    try {
      const url = sync ? '/api/sms?sync=1' : '/api/sms';
      const res = await fetch(url);
      if (res.status === 401) {
        loggedInState = false;
        updateLoginState();
        return;
      }
      const data = await res.json();
      if (data && data.messages) {
        messages = data.messages;
      }
    } catch (err) {
      console.error('[SMS-Inbox] Fetch error:', err);
    } finally {
      isLoading = false;
      updateRefreshBtnState();
      renderAllSmsViews();
      updateBadges();
    }
  }

  function updateRefreshBtnState() {
    document.querySelectorAll('.sms-refresh-btn').forEach(btn => {
      if (isLoading) {
        btn.textContent = 'Loading...';
        btn.disabled = true;
      } else {
        btn.textContent = 'Refresh';
        btn.disabled = false;
      }
    });
  }

  function updateBadges() {
    const count = messages.length;
    document.querySelectorAll('.sms-badge-count').forEach(el => {
      if (count > 0) {
        el.textContent = count;
        el.style.display = 'inline-block';
      } else {
        el.style.display = 'none';
      }
    });
    document.querySelectorAll('.sms-total-text').forEach(el => {
      el.textContent = `(${count} messages)`;
    });
  }

  function copyToClipboard(text, label) {
    const successMsg = `${label || 'Text'} copied to clipboard!`;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => {
        showToast(successMsg);
      }).catch(() => {
        fallbackCopy(text, successMsg);
      });
    } else {
      fallbackCopy(text, successMsg);
    }
  }

  function fallbackCopy(text, successMsg) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      showToast(successMsg);
    } catch (_) {}
    document.body.removeChild(ta);
  }

  function showToast(msg) {
    let toast = document.getElementById('sms-toast-notify');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'sms-toast-notify';
      toast.style.cssText = `
        position: fixed; top: 24px; left: 50%; transform: translateX(-50%);
        background: #303133; color: #fff; padding: 10px 22px; border-radius: 4px;
        font-size: 13px; font-weight: 500; z-index: 9999999;
        box-shadow: 0 4px 16px rgba(0,0,0,0.25); transition: opacity 0.3s;
      `;
      document.body.appendChild(toast);
    }
    toast.textContent = msg;
    toast.style.opacity = '1';
    toast.style.display = 'block';
    setTimeout(() => {
      toast.style.opacity = '0';
      setTimeout(() => { toast.style.display = 'none'; }, 300);
    }, 2000);
  }

  // Generate Table HTML
  function buildTableRowsHtml() {
    if (messages.length === 0) {
      return `
        <tr>
          <td colspan="5" style="text-align: center; padding: 50px 20px; color: #909399; font-size: 14px;">
            <b>No messages in inbox.</b><br>
            <span style="font-size: 12px; color: #b4b4b4;">Incoming OTP or data quota SMS will appear here automatically.</span>
          </td>
        </tr>
      `;
    }

    return messages.map((msg, idx) => {
      const otp = msg.otp || detectOtp(msg.body);
      let otpCell = '<span style="color: #c0c4cc;">-</span>';
      if (otp) {
        otpCell = `
          <div style="display: flex; align-items: center; gap: 8px;">
            <span style="font-size: 15px; font-weight: bold; letter-spacing: 2px; color: #f56c6c; background: #fef0f0; border: 1px solid #fde2e2; padding: 2px 8px; border-radius: 4px;">
              ${escapeHtml(otp)}
            </span>
            <button type="button" class="sms-copy-btn" data-copy-val="${escapeHtml(otp)}" data-copy-lbl="OTP Code" style="padding: 3px 8px; font-size: 12px; cursor: pointer; background: #ecf5ff; border: 1px solid #d9ecff; color: #409eff; border-radius: 4px;">
              Copy
            </button>
          </div>
        `;
      }

      let bodyText = escapeHtml(msg.body || '').replace(/\n/g, '<br>');
      if (otp) {
        bodyText = bodyText.split(escapeHtml(otp)).join(`<mark style="background: #ffe066; padding: 1px 4px; border-radius: 2px; font-weight: bold;">${escapeHtml(otp)}</mark>`);
      }

      return `
        <tr style="border-bottom: 1px solid #ebeef5; ${idx % 2 === 1 ? 'background: #fafafa;' : ''}">
          <td style="padding: 12px 14px; font-weight: 600; color: #303133; white-space: nowrap;">
            <span style="background: #f4f4f5; border: 1px solid #e9e9eb; color: #909399; padding: 2px 8px; border-radius: 4px; font-size: 12px;">
              ${escapeHtml(msg.sender || 'Unknown')}
            </span>
          </td>
          <td style="padding: 12px 14px; color: #606266; font-size: 13px; white-space: nowrap;">
            ${escapeHtml(msg.date || '-')}
          </td>
          <td style="padding: 12px 14px; color: #303133; font-size: 13px; line-height: 1.5; word-break: break-word;">
            ${bodyText}
          </td>
          <td style="padding: 12px 14px; white-space: nowrap;">
            ${otpCell}
          </td>
          <td style="padding: 12px 14px; text-align: center; white-space: nowrap;">
            <button type="button" class="sms-copy-btn" data-copy-val="${encodeURIComponent(msg.body || '')}" data-copy-lbl="Message body" data-encoded="1" style="background: none; border: none; color: #409eff; font-size: 12px; cursor: pointer;">
              Copy Text
            </button>
          </td>
        </tr>
      `;
    }).join('');
  }

  function renderAllSmsViews() {
    const rowsHtml = buildTableRowsHtml();
    document.querySelectorAll('.sms-table-tbody').forEach(tbody => {
      tbody.innerHTML = rowsHtml;
    });

    // Attach click events
    document.querySelectorAll('.sms-copy-btn').forEach(btn => {
      btn.onclick = (e) => {
        e.stopPropagation();
        let val = btn.getAttribute('data-copy-val');
        if (btn.getAttribute('data-encoded') === '1') {
          val = decodeURIComponent(val);
        }
        copyToClipboard(val, btn.getAttribute('data-copy-lbl') || 'Text');
      };
    });
  }

  // Generates the full SMS Inbox Widget container
  function createInboxWidgetHtml(isStandalone = false) {
    return `
      <div style="background: #fff; border: ${isStandalone ? '1px solid #e4e7ed' : 'none'}; border-radius: 6px; padding: 16px; box-sizing: border-box; min-height: 520px;">
        <!-- Top Toolbar -->
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; border-bottom: 1px solid #ebeef5; padding-bottom: 14px; flex-wrap: wrap; gap: 10px;">
          <div style="display: flex; align-items: center; gap: 10px;">
            <span style="font-size: 17px; font-weight: 700; color: #303133;">Incoming SMS & OTP Inbox</span>
            <span class="sms-total-text" style="font-size: 13px; color: #909399;">(0 messages)</span>
          </div>
          <div style="display: flex; gap: 10px; align-items: center;">
            <label style="font-size: 13px; color: #606266; display: flex; align-items: center; gap: 6px; cursor: pointer; user-select: none;">
              <input type="checkbox" class="sms-auto-refresh-toggle" checked style="cursor: pointer;"> Auto-refresh (10s)
            </label>
            <button type="button" class="sms-sync-btn" title="Sync SIM database" style="background: #fff; border: 1px solid #dcdfe6; color: #606266; padding: 6px 14px; border-radius: 4px; font-size: 12px; cursor: pointer;">
              Sync SIM
            </button>
            <button type="button" class="sms-refresh-btn" style="background: #409eff; border: 1px solid #409eff; color: #fff; padding: 6px 14px; border-radius: 4px; font-size: 12px; font-weight: 600; cursor: pointer;">
              Refresh
            </button>
          </div>
        </div>

        <!-- Table -->
        <div style="width: 100%; border: 1px solid #ebeef5; border-radius: 4px; overflow-x: auto;">
          <table style="width: 100%; border-collapse: collapse; text-align: left;">
            <thead>
              <tr style="background: #fafafa; border-bottom: 1px solid #ebeef5; color: #909399; font-size: 13px; font-weight: 600;">
                <th style="padding: 12px 14px; width: 140px;">Sender</th>
                <th style="padding: 12px 14px; width: 170px;">Received At</th>
                <th style="padding: 12px 14px;">Message Body</th>
                <th style="padding: 12px 14px; width: 160px;">OTP Code</th>
                <th style="padding: 12px 14px; width: 90px; text-align: center;">Action</th>
              </tr>
            </thead>
            <tbody class="sms-table-tbody">
              <tr>
                <td colspan="5" style="text-align: center; padding: 40px; color: #909399;">Loading SMS messages...</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    `;
  }

  // Standalone page render export
  window.renderStandaloneInbox = function (targetEl) {
    if (!targetEl) return;
    targetEl.innerHTML = createInboxWidgetHtml(true);
    bindWidgetEvents(targetEl);
    fetchMessages();
    startAutoRefresh();
  };

  function bindWidgetEvents(container) {
    container.querySelectorAll('.sms-refresh-btn').forEach(b => {
      b.onclick = () => fetchMessages(false);
    });
    container.querySelectorAll('.sms-sync-btn').forEach(b => {
      b.onclick = () => fetchMessages(true);
    });
    container.querySelectorAll('.sms-auto-refresh-toggle').forEach(t => {
      t.onchange = (e) => {
        if (e.target.checked) startAutoRefresh();
        else stopAutoRefresh();
      };
    });
  }

  // Floating button: only appears when logged in
  function getOrCreateFloatingButton() {
    let btn = document.getElementById('sms-floating-btn');
    if (!btn) {
      btn = document.createElement('div');
      btn.id = 'sms-floating-btn';
      btn.style.cssText = `
        position: fixed; bottom: 24px; right: 24px; z-index: 999999;
        background: #409eff; color: #fff; padding: 9px 18px; border-radius: 24px;
        box-shadow: 0 4px 14px rgba(64,158,255,0.4); cursor: pointer;
        font-size: 13px; font-weight: 600; display: none; align-items: center;
        gap: 8px; user-select: none; transition: transform 0.2s ease, box-shadow 0.2s ease;
      `;
      btn.innerHTML = `
        <span>SMS Inbox</span>
        <span class="sms-badge-count" style="display: none; background: #f56c6c; color: #fff; border-radius: 10px; padding: 1px 7px; font-size: 11px;">0</span>
      `;

      btn.onmouseenter = () => {
        btn.style.transform = 'translateY(-2px) scale(1.02)';
        btn.style.boxShadow = '0 6px 18px rgba(64,158,255,0.55)';
      };
      btn.onmouseleave = () => {
        btn.style.transform = 'translateY(0) scale(1)';
        btn.style.boxShadow = '0 4px 14px rgba(64,158,255,0.4)';
      };
      btn.onclick = (e) => {
        e.preventDefault();
        openModalInbox();
      };

      document.body.appendChild(btn);
    }
    return btn;
  }

  // Modal Dialog for viewing SMS
  function openModalInbox() {
    let modal = document.getElementById('sms-modal-dialog');
    if (!modal) {
      modal = document.createElement('div');
      modal.id = 'sms-modal-dialog';
      modal.style.cssText = `
        position: fixed; top: 0; left: 0; width: 100%; height: 100%;
        background: rgba(0,0,0,0.5); z-index: 9999999; display: flex;
        align-items: center; justify-content: center; backdrop-filter: blur(2px);
      `;
      modal.innerHTML = `
        <div style="background: #fff; width: 92%; max-width: 950px; max-height: 90vh; border-radius: 8px; box-shadow: 0 8px 32px rgba(0,0,0,0.25); display: flex; flex-direction: column; overflow: hidden;">
          <div style="padding: 14px 20px; border-bottom: 1px solid #ebeef5; display: flex; justify-content: space-between; align-items: center; background: #fafafa;">
            <div style="display: flex; align-items: center; gap: 8px;">
              <span style="font-size: 16px; font-weight: 700; color: #303133;">SMS Inbox & OTP Reader</span>
            </div>
            <button id="sms-modal-close" style="background:none; border:none; font-size: 22px; cursor: pointer; color: #909399; line-height: 1; padding: 0 4px;">&times;</button>
          </div>
          <div id="sms-modal-body" style="padding: 16px; overflow-y: auto; flex: 1;">
            ${createInboxWidgetHtml(false)}
          </div>
        </div>
      `;
      document.body.appendChild(modal);
      document.getElementById('sms-modal-close').onclick = () => { modal.style.display = 'none'; };
      modal.onclick = (e) => { if (e.target === modal) modal.style.display = 'none'; };
      bindWidgetEvents(modal);
    }

    modal.style.display = 'flex';
    fetchMessages();
  }

  // Update floating button visibility based on login state
  function updateLoginState() {
    const isLogged = isUserLoggedIn();
    const btn = getOrCreateFloatingButton();

    if (isLogged) {
      if (btn.style.display !== 'flex') {
        btn.style.display = 'flex';
      }
      if (!loggedInState) {
        loggedInState = true;
        fetchMessages();
        startAutoRefresh();
      }
    } else {
      if (btn.style.display !== 'none') {
        btn.style.display = 'none';
      }
      const modal = document.getElementById('sms-modal-dialog');
      if (modal && modal.style.display !== 'none') {
        modal.style.display = 'none';
      }
      if (loggedInState) {
        loggedInState = false;
        stopAutoRefresh();
        fetch('/api/logout').catch(() => {});
      }
    }
  }

  function startAutoRefresh() {
    stopAutoRefresh();
    autoRefreshTimer = setInterval(() => {
      if (loggedInState) {
        fetchMessages(false);
      }
    }, 10000);
  }

  function stopAutoRefresh() {
    if (autoRefreshTimer) {
      clearInterval(autoRefreshTimer);
      autoRefreshTimer = null;
    }
  }

  // Intercept sessionStorage.setItem to detect SPA login instantly
  try {
    const originalSetItem = Storage.prototype.setItem;
    sessionStorage.setItem = function (k, v) {
      originalSetItem.apply(sessionStorage, arguments);
      if (k === 'session' || k.toLowerCase().includes('token')) {
        setTimeout(updateLoginState, 50);
      }
    };
  } catch (_) {}

  window.addEventListener('hashchange', () => setTimeout(updateLoginState, 100));
  window.addEventListener('popstate', () => setTimeout(updateLoginState, 100));

  // MutationObserver for DOM route changes
  const observer = new MutationObserver(() => {
    updateLoginState();
  });

  observer.observe(document.body, { childList: true, subtree: true });

  // Initial check
  setTimeout(updateLoginState, 300);
  setInterval(updateLoginState, 1500);

})();
