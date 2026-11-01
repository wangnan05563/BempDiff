// BempDiff Electron preload —— 仅暴露文件选择桥给渲染进程。
// 设计原则：
//   - 渲染进程只能通过 contextBridge 拿到受控 API（sandbox + contextIsolation）。
//   - 不暴露 ipcRenderer 自身，避免渲染进程误用任意 channel。
//   - 与 webui/src/lib/tauri.js 的 pickPath(opts) 签名保持一致，
//     前端无需关心是 Electron 还是 Tauri 环境。

const { contextBridge, ipcRenderer } = require('electron')

// 当前产品版本（安装包版本）：沙箱 preload 拿不到 process.env，故在主进程注册 handler 后
// 同步取一次常量。前端据此把「当前版本」传给 /api/update/check——此前壳层未暴露版本号，
// 前端只能回落内置 APP_VERSION('0.1.0')，导致最新版安装包也恒报「发现新版本」。
function readAppVersion() {
  try {
    return String(ipcRenderer.sendSync('bempdiff:app-version') || '')
  } catch (_) {
    return ''
  }
}

contextBridge.exposeInMainWorld('bempdiff', {
  /** 安装包版本号（app.getVersion()）；浏览器模式下不存在该桥，前端自行回落。 */
  appVersion: readAppVersion(),
  /**
   * 调主进程 dialog.showOpenDialog 选择文件或文件夹。
   * @param {{directory?: boolean, multiple?: boolean}} [opts]
   * @returns {Promise<string|string[]|null>} 绝对路径；取消返回 null；多选时返回数组。
   */
  pickPath(opts = {}) {
    return ipcRenderer.invoke('bempdiff:pick-path', opts)
  },
  /**
   * 差异树右键「打开」：用系统默认程序打开文件 / 打开文件夹。
   * @param {string} p 绝对路径
   * @returns {Promise<string>} 空串=成功；否则为错误信息
   */
  openPath(p) {
    return ipcRenderer.invoke('bempdiff:open-path', p)
  },
  /**
   * 差异树右键「在文件资源管理器中显示」：定位到资源管理器并选中。
   * @param {string} p 绝对路径
   * @returns {Promise<string>} 空串=成功；否则为错误信息
   */
  showInFolder(p) {
    return ipcRenderer.invoke('bempdiff:show-in-folder', p)
  },
  /**
   * 一键更新：下载 GitHub Release 的 .exe 安装包到系统下载目录并启动安装器。
   * @param {string} url GitHub Release 资产 .exe 的 https 下载地址
   * @returns {Promise<{ok:boolean, file?:string, message?:string}>}
   */
  downloadInstall(url) {
    return ipcRenderer.invoke('bempdiff:download-install', url)
  },
  /**
   * 读取「关闭窗口行为」配置（最小化到托盘 / 直接关闭 + 是否记住选择）。
   * @returns {Promise<{ok:boolean, close_action:string, remember_choice:boolean}>}
   */
  getCloseBehavior() {
    return ipcRenderer.invoke('bempdiff:get-close-behavior')
  },
  /**
   * 更新「关闭窗口行为」配置：主进程立即落盘，下一次关闭按新配置执行。
   * @param {{close_action?: string, remember_choice?: boolean}} cfg
   * @returns {Promise<{ok:boolean, close_action:string, remember_choice:boolean}>}
   */
  setCloseBehavior(cfg) {
    return ipcRenderer.invoke('bempdiff:set-close-behavior', cfg)
  },
  /**
   * 注册「外部入口传入比对路径」监听（右键菜单 / 命令行参数）。
   * 主进程在收到文件参数时通过 webContents.send('bempdiff:shell-compare', paths) 推送；
   * 渲染进程（store.init）据此自动填路径并比对。
   * @param {(paths: string[]) => void} cb
   */
  onShellCompare(cb) {
    ipcRenderer.on('bempdiff:shell-compare', (_e, paths) => {
      if (typeof cb === 'function') cb(paths)
    })
  }
})