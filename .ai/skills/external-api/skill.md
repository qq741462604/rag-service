# External API Client Skill

Keywords:

- 接口
- 外调
- HTTP
- REST
- RestTemplate
- 调用接口
- timeout

Target:

class: DataPrepEventHandler
method: handle
type: existing

Rules:

1. 外部接口调用链必须是 application.yml -> XxxApiConfig -> XxxApiClient -> Target.handle。
2. Target.handle 只允许获取业务字段、调用 Client、使用 eventData.putDataAndOriginData 写回结果。
3. Target 中禁止出现 RestTemplate、URL、timeout、HTTP 请求代码、JSON 解析代码。
4. Target 原本不是 Spring Bean 时，禁止新增 @Component、@Service、@RequiredArgsConstructor，禁止改变构造器、继承关系和生命周期。
5. 如果无法在不破坏 Target 生命周期的情况下获取 Client，必须先读取已有 Handler 依赖获取样例，不能声明未初始化字段。
6. URL、method、timeout 必须来自 application.yml 和 XxxApiConfig，禁止硬编码到 Java 代码。
7. 用户任务包含 timeout 时，timeout 必须真实设置到 RestTemplate 的 connectTimeout 和 readTimeout。
8. 如果不存在可证明已配置 timeout 的业务 RestTemplate Bean，必须创建业务命名 RestTemplate 配置类，例如 UserApiRestTemplateConfig。
9. 每个外部接口只能有一个业务 RestTemplateConfig 和一个业务 RestTemplate Bean；创建前必须 search_code 检查是否已存在。
10. XxxApiClient 必须是 @Component，使用构造器注入，字段必须 final，禁止 @Autowired 字段注入、@Value、new RestTemplate。
11. XxxApiClient 必须返回 DTO，禁止直接修改 EventData。
12. 接口返回 JSON 必须生成 DTO，禁止在 Target 中使用 Map 解析业务字段。
13. Config、Client、DTO 必须创建到 Target 业务根包下的 config、client、dto 包，不要创建到 Target 所在 extension 包。
14. 如果 Target package 以 .extension 结尾，业务根包取去掉 .extension 后的包名。
15. Target 写回结果只能使用 eventData.putDataAndOriginData，禁止 eventFields.put、eventData.set 或直接修改 eventData.getData() 返回的 Map。
16. 新增 Java 类必须包含正确 package、import、类级 Javadoc；跨 config、client、dto 包引用时必须显式 import。
17. 如果 Target 已知且已存在，必须先 search_code/read_file 读取 Target 真实路径和 package，再生成新类；新类 package 必须从 Target 真实 package 推导，禁止使用 com.example、demo、example 之类的兜底包名。
18. 如果 Target package 以 .extension 结尾，新类必须落在去掉 .extension 后的业务根包下的 config、client、dto 包中。
19. 所有模板示例仅作结构参考，禁止直接照抄示例中的 package 名、类名或路径名。

Implementation:

Step1:
search_code 定位 Target 类。

Step2:
read_file 读取完整 Target。

Step3:
search_code 检查已有 RestTemplate、@Configuration、@Bean、RestTemplateConfig、业务 RestTemplate Bean、Config、Client、DTO。

Step4:
如果 application.yml 已存在，read_file 后 patch_file 增加接口配置；如果不存在，write_file 创建。

Step5:
如果 XxxApiConfig 不存在，write_file 创建；如果已存在，read_file 后 patch_file 修改。

Step6:
如果业务 RestTemplateConfig 不存在且需要 timeout，write_file 创建；如果已存在，read_file 后 patch_file 修改。

Step7:
如果 XxxApiClient 不存在，write_file 创建；如果已存在，read_file 后 patch_file 修改。

Step8:
如果 DTO 不存在，write_file 创建；如果已存在，read_file 后 patch_file 修改。

Step9:
最后 patch_file 修改 Target.handle 调用 Client，并使用 eventData.putDataAndOriginData 写回字段。

RestTemplateConfig 必需模板，生成时必须补齐实际 package 和 import:

```java
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@RequiredArgsConstructor
public class UserApiRestTemplateConfig {

    private final UserApiConfig config;

    @Bean("userApiRestTemplate")
    public RestTemplate userApiRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getTimeout());
        factory.setReadTimeout(config.getTimeout());
        return new RestTemplate(factory);
    }
}
```

Client 必需模板，生成时必须补齐实际 package 和 import:

```java
import com.study.plugin.config.UserApiConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class UserApiClient {

    private final RestTemplate restTemplate;
    private final UserApiConfig config;

    public UserApiClient(
            @Qualifier("userApiRestTemplate") RestTemplate restTemplate,
            UserApiConfig config
    ) {
        this.restTemplate = restTemplate;
        this.config = config;
    }
}
```

Forbidden:

- create Target
- replace Target
- modify Target signature
- modify Target parent class
- create Controller
- HTTP code in handle
- hardcode URL
- hardcode timeout
- new RestTemplate
- HttpURLConnection
- JSON 解析放 Target
- Client 直接 put EventData
- static 接口配置
- 按示例自由改写模板
- ApiConfig 保存 RestTemplate
- Target 引用不存在类
- write_file 修改 Target
- com.example 兜底包名
