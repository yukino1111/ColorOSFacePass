# ColorOSFaceBiometricFix

[![Android](https://img.shields.io/badge/Android-14%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#)
[![ColorOS](https://img.shields.io/badge/ColorOS-14%20%7C%2015%20%7C%2016-00C775?style=for-the-badge)](#)
[![LibXposed](https://img.shields.io/badge/LibXposed-API_101-ff69b4?style=for-the-badge)](#)

专为 ColorOS 打造的生物识别全能增强模块，二合一整合 **人脸弱生物识别提权** 与 **自动确认免二次点击**，并配备现代化 Material 3 应用规则管理界面。

---

## ✨ 核心特性

- 🔓 **ColorOS 人脸提权 (Class 3 增强)**
  - 拦截 `PreAuthInfo` 与 `Utils.isAtLeastStrength`，将 ColorOS 默认上报的 4095 私有弱强度提升为 Class 3 (15 强生物识别)；
  - 完美解决微信支付、支付宝、手机银行及各类第三方 App 无法使用人脸识别的问题。

- ⚡ **智能秒点与全无感隐藏 (Biometric Bypass)**
  - 识别成功后自动跳过繁琐的“点击确认”环节；
  - 支持“隐藏确认弹窗”全局开关，实现透明极速通过，体验媲美旗舰级 3D 结构光。

- 🎨 **Material 3 全功能配置中心**
  - **黑白名单随心换**：支持白名单模式（仅勾选生效）与黑名单模式（勾选排除）；
  - **精准分类检索**：支持按应用名称与包名双向实时检索，一键切换「全部 / 用户 / 系统」应用；
  - **极速操作**：支持全局一键全选 / 全不选，以及标准 JSON 规则导入导出。

- 🚀 **原生 ContentProvider 实时直连**
  - 底层 Hook 与 App 配置采用原生 ContentProvider 毫秒级直连传输；
  - **在 App 里任何时候勾选/取消应用，下一秒打开软件 100% 实时生效，日常配置无需重启设备！**

---

## 📲 安装与使用指南

1. 确保系统已激活 **LSPosed** 框架（支持现代 LibXposed 规范）；
2. 下载并安装最新版 Release APK；
3. 在 LSPosed 管理器中启用模块，并确保勾选推荐的作用域：
   - **系统框架 (System Framework / `system`)** —— 用于人脸 4095 提权；
   - **系统界面 (System UI / `com.android.systemui`)** —— 用于自动跳过点击与隐藏弹窗；
4. **仅首次安装激活时**：软重启一次系统框架（或重启手机一次）以加载底层 Hook；
5. 打开桌面应用，在白名单中勾选需要启用人脸识别与免确认的应用即可，即勾即生效！

---

## 📜 开源许可

本项目基于 MIT 许可开源。
