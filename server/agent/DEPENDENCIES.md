# LangChain4j 最小接入依赖审计

用户已明确批准加入LangChain4j。仅在agent新增两个显式直接依赖：dev.langchain4j:langchain4j-core:1.22.0 与 dev.langchain4j:langchain4j-open-ai:1.22.0（core也会由open-ai传递引入，显式声明用于标明本模块直接使用的API）。不加高层langchain4j、BOM、Spring starter、插件或其他显式库，不升级Boot/Jackson。

下表按官方已发布POM及本机现有缓存只读审计，scope未注明为compile；未执行Maven实际解析，解析与兼容门禁由C4运行：

| 生产闭包 | 发布POM | Boot4.0.8预计解析 |
| --- | --- | --- |
| dev.langchain4j:langchain4j-core | 1.22.0 | 1.22.0 |
| dev.langchain4j:langchain4j-open-ai | 1.22.0 | 1.22.0 |
| dev.langchain4j:langchain4j-http-client | 1.22.0 | 1.22.0 |
| dev.langchain4j:langchain4j-http-client-jdk（runtime） | 1.22.0 | 1.22.0 |
| dev.langchain4j:langchain4j-reactive-streaming | 1.22.0-beta32 | 1.22.0-beta32 |
| com.fasterxml.jackson.core:jackson-annotations | 2.22 | 2.21 |
| com.fasterxml.jackson.core:jackson-core | 2.22.1 | 2.21.5 |
| com.fasterxml.jackson.core:jackson-databind | 2.22.1 | 2.21.5 |
| com.knuddels:jtokkit | 1.1.0 | 1.1.0 |
| io.smallrye.reactive:mutiny-zero | 1.3.1 | 1.3.1 |
| org.slf4j:slf4j-api | 2.0.18 | 2.0.18 |
| org.jspecify:jspecify | 1.0.1 | 1.0.1 |

用途：core提供消息/模型契约，open-ai提供兼容Chat API，http-client与jdk运行时实现HTTP，reactive-streaming/mutiny-zero为库既有流式设施（MVP不启用流式），Jackson2为供应商JSON，jtokkit为token计数，slf4j为日志API，jspecify为空值注解。Boot4现有Jackson3坐标与此Jackson2并存；不将Jackson3当作对2的自动替换。无需opennlp-tools，该库只由不引入的高层AI Services包依赖。

C4必须保存实际 dependency:tree 与有效版本证据，确认上述闭包、scope、无额外新库或版本漂移；检查Jackson覆盖后的编译和真实文本/图像最小调用。不兼容时先报告确定证据，不静默升级版本。

官方来源：
- https://raw.githubusercontent.com/langchain4j/langchain4j/1.22.0/langchain4j-open-ai/pom.xml
- https://raw.githubusercontent.com/langchain4j/langchain4j/1.22.0/langchain4j-core/pom.xml
- https://raw.githubusercontent.com/langchain4j/langchain4j/1.22.0/langchain4j-parent/pom.xml
- https://raw.githubusercontent.com/langchain4j/langchain4j/1.22.0/langchain4j-bom/pom.xml
- https://raw.githubusercontent.com/spring-projects/spring-boot/v4.0.8/platform/spring-boot-dependencies/build.gradle

1.22.0实现确认baseUrl保留完整路径前缀后追加chat/completions。配置使用供应商完整前缀，不能强制归一到主机根路径。
