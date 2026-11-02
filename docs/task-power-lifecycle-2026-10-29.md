# 后台任务电源生命周期：第五批

任务前台服务增加 `PARTIAL_WAKE_LOCK` 租约。仅有正在执行的用户任务时持有，每次最长 120 秒、活跃期间每 60 秒续期；服务销毁、任务完成或停止时释放。所有剩余任务都在等待设备操作确认或使用情况权限时释放，不因等待用户而持续阻止 CPU 休眠。锁只维持计算，不点亮屏幕，也不跳过锁屏。

沿用现有可见任务通知和“停止”入口，服务仍为 `START_NOT_STICKY`。未增加开机任务、常驻 Root 守护、持续录音或自动重放中断的操作。

## 已验证

- 单元测试覆盖非引用计数、续期后有效期、自动超时和重复释放；实际运行时的确认等待用例检查此时无可执行后台工作。
- `build/task-power-lease-phone-active-stop.log`：iQOO 真机 2 项通过。Activity 关闭后合成本地流继续完成，新界面能停止既有任务；通知按钮停止另一任务。真实 `dumpsys power` 确认执行时存在 `Floryx:UserTask`，结束/停止后锁与前台服务均退出。
- 诊断只使用本机 HTTP 合成模型，显式选择并核对合成会话，恢复测试前的设置与会话选择并清理测试记录。

第六批最终包已在 `build/task-power-lease-phone-screen-off.log` 通过熄屏测试：Activity 关闭、实际屏幕熄灭，合成本地任务持续 30 秒后在未亮屏时完成，随后任务服务与 `Floryx:UserTask` 均退出。用例结束才发送亮屏按键，保留系统安全锁屏。该用例验证计算/网络任务；不据此声称锁屏时可操作第三方应用、长时间 Doze 或实际续航已经通过。

参考：[Android 唤醒锁使用](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock)、[带超时的 WakeLock](https://developer.android.com/reference/android/os/PowerManager.WakeLock#acquire(long))。
