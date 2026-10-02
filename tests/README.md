活塞动画场景回归测试：

```powershell
.\gradlew.bat -I tests/piston-smoke.gradle runClient
```

测试会在开发客户端中创建独立的空白世界和 Y=70 的投影，循环执行黏性活塞推动、拉回石头。它检查实际画面提交路径中是否出现带覆盖层的活塞头和被推动方块的中间位置，结束后自动关闭客户端；没有动画提交时构建会失败。

场景中包含静止石头作颜色对照，结束前还会验证 48 个模型案例的绘制顺序及覆盖层合成目标。结果写入 `run/piston-scene-result.txt`，过程截图保存在 `run/screenshots/`。测试类使用独立的构建目录，不进入正常 `build` 生成的成品 JAR。可将需要复测的渲染模组放入开发实例的 `run/mods/`。

骨粉机 200 TPS 实时测试：

```powershell
.\gradlew.bat -I tests/tps-smoke.gradle runClient
```

默认加载用户提供的 `D:/Games/Minecraft/.minecraft/versions/26.2-Fabric 0.19.3/schematics/jiqi/无粘抗卸载骨粉机-方块替换.litematic`，激活原有拉杆，预热 1,200 个模拟 tick 后测量现实 60 秒。不修改原文件，仅在内存中向下扩大测试边界，收集原有底部输出的骨粉。结果写入 `run/tps-scene-result.txt`，包含实际 TPS、推进的模拟时间、骨粉数量及按模拟时间计算的每小时产量；性能不足单独标记，机器停止产出则失败。

控制面板与指令回归测试：

```powershell
.\gradlew.bat -I tests/ui-smoke.gradle runClient
```

检查不同窗口尺寸下的布局、滚动裁剪、指令补全与执行，以及候选列表的点击和滚轮交互。将兼容的 Carpet 模组放入 `run/mods/` 可同时检查假人行对齐。结果写入 `run/ui-layout-result.txt`，截图保存在 `run/screenshots/`；测试代码不进入正式 JAR。
