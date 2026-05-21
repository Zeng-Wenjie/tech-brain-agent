/**
 * Tech-Brain Tool Calling 公共能力包。
 *
 * <p>当前模块只创建 Maven 子模块和包结构，不承载任何业务工具实现。</p>
 * <p>后续可在该包下逐步放入 DeepSeekClient、AiTool、AbstractAiTool、ToolRegistry、ToolCallingChatService 等通用框架能力。</p>
 * <p>调用链规划为：业务模块依赖 Tech-Brain-Tool，业务代码注册具体 Tool，通用 Tool Calling 服务负责模型调用和工具分发。</p>
 */
package com.agent.toolcalling; // Tool Calling 公共框架能力的预留包名。
