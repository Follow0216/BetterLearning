# LangChain4j 依赖版本记录

`agent` 声明 `dev.langchain4j:langchain4j-core:1.22.0` 与 `dev.langchain4j:langchain4j-open-ai:1.22.0` 为直接依赖；core 也由 open-ai 传递引入，显式声明标明本模块直接使用其 API。

下表是根据发布 POM 记录的依赖闭包候选，scope 未注明为 compile；具体解析结果以当前 Maven dependency:tree 为准：

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

升级依赖时，应检查实际 dependency:tree、scope、Jackson 版本与兼容性；真实文本和图像调用仍需单独验证。

官方来源：
- https://raw.githubusercontent.com/langchain4j/langchain4j/1.22.0/langchain4j-open-ai/pom.xml
- https://raw.githubusercontent.com/langchain4j/langchain4j/1.22.0/langchain4j-core/pom.xml
- https://raw.githubusercontent.com/langchain4j/langchain4j/1.22.0/langchain4j-parent/pom.xml
- https://raw.githubusercontent.com/langchain4j/langchain4j/1.22.0/langchain4j-bom/pom.xml
- https://raw.githubusercontent.com/spring-projects/spring-boot/v4.0.8/platform/spring-boot-dependencies/build.gradle

1.22.0实现确认baseUrl保留完整路径前缀后追加chat/completions。配置使用供应商完整前缀，不能强制归一到主机根路径。
