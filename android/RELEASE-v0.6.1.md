# PawLink Capture 0.6.1

记录页增加逐条删除：点击「删除」后确认会话名称及删除范围，再「永久删除」。会删除该会话的视频、IMU、候选标签、设备证据和全部复核版本。已导出的 ZIP 保留。

正在采集的会话不可删除；后台执行文件删除，期间禁用该记录的打开与删除按钮。失败时保留剩余文件并提示重试。路径校验限制在 sessions 的直接子目录，递归删除不跟随符号链接。

JDK 17 独立检查：编译 SessionStorage.java 和 tests/SessionStorageCheck.java 后运行 com.pawlink.capture.SessionStorageCheck。覆盖递归删除、活动会话保护、根目录/越界路径拒绝、符号链接隔离和重复删除。
