# 验证证据与实测说明

本轮验收仅为独立 Mesh 引擎预研。**hardware validation pending**；没有无线吞吐、手机电池或后台存活成绩。

协议/核心、资源边界、独立进程和隔离 TCP 实验分别验证，不以 LAN 通信替代无线结论。最终 release 测量及源文件清单位于 `mesh/evidence/`；初期 `isolated-debug.json` 与 `isolated-discovery-debug.json` 是修复完成前的探索运行，不作为最终 revision 验收。

必要验证命令见 `mesh/README.md`。正式客户端/NIM4/Noise/版本与构建输入保持不变，因此没有重新执行无关客户端构建或发布检查。
