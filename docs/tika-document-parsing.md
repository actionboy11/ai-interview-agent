# 基于 Apache Tika 的多格式文档提取与解析链路

> 范围：`app/src/main/java/interview/guide/infrastructure/file/` 下的 7 个类，及其在简历模块、知识库模块中的调用方式。
> 本文按「做了什么 → 代码怎么实现 → 为什么这样做」组织。

---

## 一、这条链路解决什么问题

用户上传的是**二进制文件**（PDF / DOCX / DOC / TXT / MD / RTF），而下游的 LLM 分析、向量化、RAG 检索需要的都是**纯文本**。中间这一层的职责是：

1. **识别**：这是不是我们支持的文件类型？（不看扩展名，看内容）
2. **拦截**：文件是不是空的、是不是太大了？
3. **去重**：这个文件之前传过没有？（避免重复花 LLM 的钱）
4. **提取**：把二进制变成文本
5. **清洗**：把提取出来的噪声（图片引用、临时路径、控制字符、多余空行）去掉，得到能直接喂给模型的文本

这条链路**被简历模块和知识库模块共用**，所以放在 `infrastructure/file/` 而不是任何一个业务模块里。

---

## 二、整体链路

```
上传文件 (MultipartFile)
    │
    ├─ ① 空文件 + 大小校验 ....... FileValidationService.validateFile
    │
    ├─ ② MIME 类型检测 ........... ContentTypeDetectionService (Tika.detect)
    │      └─ 白名单校验 ......... FileValidationService
    │
    ├─ ③ SHA-256 内容指纹 ........ FileHashService
    │      └─ 命中则直接返回历史结果，不再解析
    │
    ├─ ④ Tika 解析提取正文 ....... DocumentParseService.parseContent
    │      ├─ AutoDetectParser 自动选解析器
    │      ├─ BodyContentHandler 只收正文 + 5MB 闸门
    │      ├─ NoOpEmbeddedDocumentExtractor 跳过嵌入资源
    │      └─ PDFParserConfig 关闭图片、按坐标排序
    │
    ├─ ⑤ 文本清洗与规范化 ........ TextCleaningService.cleanText
    │
    └─ ⑥ 消费方分流
           ├─ 简历：文本存库(resume_text) + 原文存 RustFS → 异步 AI 评分
           └─ 知识库：原文存 RustFS → 异步向量化（消费时按 storageKey 重新下载解析）
```

---

## 三、代码结构

7 个类，三层职责：

| 类 | 职责 |
|---|---|
| `ContentTypeDetectionService` | MIME 类型检测（基于内容 + 文件名提示），并暴露 `isPdf/isWordDocument/isPlainText/isMarkdown` 判断 |
| `FileValidationService` | 通用校验：空/大小校验、MIME 白名单校验（支持扩展名兜底） |
| `FileHashService` | SHA-256 内容指纹，用于秒传去重 |
| `DocumentParseService` | **核心**：Tika 解析，三个入口（MultipartFile / byte[] / 从存储下载） |
| `NoOpEmbeddedDocumentExtractor` | 空实现的嵌入文档提取器，用于**禁用** Tika 解析文档内嵌资源 |
| `TextCleaningService` | 文本清洗规范化（正则去噪 + 格式规整） |
| `FileStorageService` | S3 兼容对象存储；本链路用到它的 `downloadFile`（异步任务回源解析的入口） |

**设计要点**：业务模块不直接用这些类，而是各自有一个薄封装做委托 ——
`ResumeParseService`（`modules/resume/service/ResumeParseService.java`）和
`KnowledgeBaseParseService`（`modules/knowledgebase/service/KnowledgeBaseParseService.java`）。
两个类方法几乎一模一样，只是日志措辞不同，好处是**解析能力只实现一次**，两个业务域各自演进互不影响。

---

## 四、逐环节详解

### 4.1 依赖与选型

`gradle/libs.versions.toml`：

```toml
tika = "2.9.2"
tika-core   = { module = "org.apache.tika:tika-core", ... }
tika-parsers = { module = "org.apache.tika:tika-parsers-standard-package", ... }
```

`app/build.gradle` 同时引入 `tika.core` 和 `tika.parsers`。

**为什么用 Tika**：它提供 `AutoDetectParser`，能按文件**魔数**自动选择合适的底层解析器（PDF→PDFBox、OOXML→POI、纯文本→直接读），对外是一套统一 API。
如果不用它，就需要自己引 PDFBox + POI + 若干解析库，再写一层「按类型分发」的逻辑。用 Tika 换来的直接收益是**新增一种格式几乎不用改代码**（换掉 `tika-parsers` 依赖包即可）。

---

### 4.2 第一步：MIME 类型检测

`ContentTypeDetectionService`（`infrastructure/file/ContentTypeDetectionService.java:21-40`）：

```java
private final Tika tika;

public ContentTypeDetectionService() {
    this.tika = new Tika();          // 构造时创建一次，后续复用
}

public String detectContentType(MultipartFile file) {
    try (InputStream inputStream = file.getInputStream()) {
        return tika.detect(inputStream, file.getOriginalFilename());
    } catch (IOException e) {
        log.warn("无法检测文件类型，使用 Content-Type 头部: {}", e.getMessage());
        return file.getContentType();     // 降级：退回使用请求头
    }
}
```

**为什么基于内容检测，而不是用 `MultipartFile.getContentType()`？**
`getContentType()` 来自 HTTP 请求头，是**客户端说了算**的：浏览器通常按扩展名猜，攻击者也可以把任意文件命名为 `.pdf` 并伪造 `Content-Type: application/pdf`。Tika 的 `detect()` 读的是**文件头字节（魔数）**，比如 PDF 开头必须是 `%PDF-`，这是内容自证，伪造成本高得多。类注释里也写了这一点（"比 HTTP 头部更准确"）。

**为什么 `detect()` 还要传文件名？**
文件名在这里是**提示（hint）而不是依据**。纯文本、Markdown 这类格式**没有魔数**，Tika 只能识别出它是文本，具体是 `.md` 还是 `.txt` 需要文件名线索。两个参数同时给，是让 Tika 在"内容判不了"时有路可退。

**为什么检测失败要降级而不是直接失败？**
`getInputStream()` 抛 IOException 属于基础设施异常，此时直接拒绝用户对体验伤害大且信息量低。降级到请求头至少让后续白名单校验有机会拦住明显非法的类型 —— **检测放宽、校验收紧**，安全性由下一环节的白名单兜底。

---

### 4.3 第二步：文件校验

`FileValidationService.validateFile`（`infrastructure/file/FileValidationService.java:27-36`）：

```java
if (file.isEmpty()) {
    throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要上传的%s文件");
}
if (file.getSize() > maxSizeBytes) {
    throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制");
}
```

**大小限制是两层**：

| 层 | 限制 | 位置 |
|---|---|---|
| 框架层 | 50MB | `application.yml:69-72` `spring.servlet.multipart.max-file-size` |
| 业务层 | 简历 10MB / 知识库 50MB | `ResumeUploadService.java:41`、`KnowledgeBaseUploadService.java:38` |

为什么两层都要：框架层是**兜底防线**，防止超大请求把内存/磁盘打满（配置项对所有上传接口生效，不好按业务区分）；业务层才是**语义限制**——一份简历不可能有 50MB，超过 10MB 基本可以判定不是正常简历，早点拒掉比解析到一半再失败体验更好。

**类型白名单两个模块不一样**：

- 简历走**配置驱动**（`ResumeUploadService.java:136-142` + `application.yml:200-204`）：
  ```
  application/pdf
  application/msword
  application/vnd.openxmlformats-officedocument.wordprocessingml.document
  text/plain
  ```
- 知识库走**代码判断**（`KnowledgeBaseUploadService.java:107-115` → `FileValidationService.isKnowledgeBaseMimeType`）：
  在上面基础上额外支持 `text/markdown`、`text/x-markdown`、`text/x-web-markdown`、`application/rtf`。

为什么不一样：简历是核心业务、允许类型希望**可运维调整**（改配置即可，不用发版）；知识库面对的资料格式更杂，需要额外处理 Markdown 的几种 MIME 变体，写成代码更直观。

**知识库还有一层扩展名兜底**（`FileValidationService.validateContentType:61-77`）：

```java
if (mimeTypeChecker.test(contentType)) return;                    // 先认 MIME
if (fileName != null && extensionChecker.test(fileName)) return;  // MIME 不认再看扩展名
throw new BusinessException(...);
```

为什么需要兜底：Markdown 没有官方统一 MIME，不同浏览器/操作系统可能检测出 `text/plain` 或其他变体，只靠 MIME 会**误杀合法文件**。扩展名检查是针对"内容检测先天识别不了"的格式的补偿。

---

### 4.4 第三步：内容指纹去重

`FileHashService`（`infrastructure/file/FileHashService.java`）：

```java
private static final String HASH_ALGORITHM = "SHA-256";

public String calculateHash(MultipartFile file) {
    return calculateHash(file.getBytes());     // 全量读入内存
}

public String calculateHash(byte[] data) {
    MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
    return bytesToHex(digest.digest(data));
}
```

数据库侧对应 `ResumeEntity.fileHash`（`@Column(nullable = false, unique = true, length = 64)`）和知识库表的同名唯一列。

**为什么用 SHA-256 而不是文件名/MD5**：
- 不用文件名：同一个文件改名再传就绕过去了，去重没意义。
- 用 SHA-256 而不是 MD5：MD5 存在已知碰撞攻击，理论上可以构造两个不同文件哈希相同从而**误判重复**（把用户 A 的文件当成用户 B 的）。SHA-256 目前没有实际可行的碰撞，64 位十六进制也正好装进 `varchar(64)`。

**去重的业务价值**：一次简历解析 + AI 评分的成本远高于一次 SHA-256 计算。命中重复时直接返回历史分析结果（`ResumeUploadService.handleDuplicateResume:147`），并把 `accessCount` 加一，**既省了 LLM 开销又让"热门简历"这个信息被记录下来**。

> ⚠️ **已知不足**：`calculateHash(MultipartFile)` 内部调 `file.getBytes()`，会把**整个文件读进 JVM 堆内存**。类里其实已经实现了流式版本 `calculateHash(InputStream)`（8KB 缓冲循环 `digest.update`），但 `grep` 全仓库**没有任何调用方**。50MB 的知识库文件在并发上传时会有明显内存压力，这是一个可以直接指出并改进的点。

---

### 4.5 第四步：核心解析

`DocumentParseService.parseContent(InputStream)`（`infrastructure/file/DocumentParseService.java:111-142`）。这个方法一共 9 个步骤，注释里也标了序号，逐个说清楚"为什么"：

```java
// 1. 自动检测解析器
AutoDetectParser parser = new AutoDetectParser();

// 2. 只接收正文，限制最大 5MB
BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);  // 5 * 1024 * 1024

// 3. 元数据对象
Metadata metadata = new Metadata();

// 4. 解析上下文
ParseContext context = new ParseContext();

// 5. 显式把 Parser 放进 Context
context.set(Parser.class, parser);

// 6. 禁用嵌入文档解析
context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

// 7. PDF 专用配置
PDFParserConfig pdfConfig = new PDFParserConfig();
pdfConfig.setExtractInlineImages(false);
pdfConfig.setSortByPosition(true);
context.set(PDFParserConfig.class, pdfConfig);

// 8. 执行解析
parser.parse(inputStream, handler, metadata, context);

// 9. 返回文本
return handler.toString();
```

#### ① `AutoDetectParser`
按魔数自动委派给具体的解析器。这里是**内容检测的第二道运用**——注意与 4.2 的区别：4.2 的 `Tika.detect()` 只回答"**是什么**"，这里的 `AutoDetectParser` 是"**由谁来解析**"。两者都基于内容，所以即使请求头撒谎，走到这一步依然会选对解析器。

#### ② `BodyContentHandler` + 5MB 上限 ⚠️ 重要
Tika 的解析结果是 SAX 事件流。我们只关心**正文文本**，所以用 `BodyContentHandler` 过滤掉结构事件；同时以 5MB 为上限，防止一个超大文档把 JVM 堆撑爆。

**这里有个容易讲错的细节，已实测确认**：`BodyContentHandler(int writeLimit)` 超过上限时**不是静默截断，而是抛异常**。

我反编译了 `tika-core-2.9.2.jar` 验证：

- `BodyContentHandler(int)` → `WriteOutContentHandler(int)` → `WriteOutContentHandler(ContentHandler, int)`
- 该构造器里 `throwOnWriteLimitReached` 被显式赋值 `true`（字节码 `iconst_1; putfield throwOnWriteLimitReached`）
- 超限时调用 `handleWriteLimitReached()`，在 `throwOnWriteLimitReached == true` 时 `throw new WriteLimitReachedException(writeLimit)`
- `WriteLimitReachedException extends org.xml.sax.SAXException`（`javap` 确认）

而 `DocumentParseService` 的 catch 块正好覆盖了 SAXException（`:63`、`:90`）：

```java
} catch (IOException | TikaException | SAXException e) {
    log.error("文件解析失败: {}", e.getMessage(), e);
    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: " + e.getMessage());
}
```

**结论**：文本超过 5MB 的文档会**解析失败**并返回业务错误，而不是"截断后继续"。这是一个 **fail-fast 的防 OOM 闸门**。
对简历场景没有影响（正常简历几十 KB），但对知识库场景需要注意：50MB 上限允许的文件，如果纯文本超过 5MB，会在解析阶段直接失败。

#### ⑤ 为什么要把 `Parser` 显式放进 `ParseContext`
Tika 的部分解析器（尤其是处理 OOXML / 复合文档时）在解析**嵌套内容**时，会从 `ParseContext` 里取出 `Parser` 实例来递归解析。如果 context 里没有，某些路径会拿不到解析器而报错。
显式放进去是一种**防御性写法**：让"递归解析嵌入资源"这条路径始终有可用的 Parser。代码注释写的是"增强健壮性"。

#### ⑥ 为什么要禁用嵌入文档解析（`NoOpEmbeddedDocumentExtractor`）
DOCX、PDF 中可能内嵌图片、Excel 表格、附件等资源。Tika 默认会把它们**当作独立的文档再解析一遍**，后果是：

1. 图片会被转成二进制/图片元数据混进正文，**污染文本质量**；
2. Tika 为了处理嵌入资源会在**磁盘上生成临时文件**，带来 IO 开销和临时文件泄漏风险；
3. 嵌入资源可能形成嵌套结构（甚至恶意构造的嵌套炸弹），解析开销不可控。

我们的场景（简历、知识库文档）**只需要正文**，所以直接实现一个返回 `false` 的提取器：

```java
@Override
public boolean shouldParseEmbedded(Metadata metadata) {
    return false;    // 始终拒绝
}
```

`NoOpEmbeddedDocumentExtractor` 的实现很有意思——`shouldParseEmbedded` 返回 `false`，`parseEmbedded` 是**空方法体**，因为 Tika 在 shouldParseEmbedded 返回 false 时不会调用它。同时它用**字符串常量** `"resourceName"` 取元数据（`NoOpEmbeddedDocumentExtractor.java:27`）而不是引用 Tika 的常量类，注释写明是"兼容不同 Tika 版本"——避免因升级 Tika 导致的编译失败。

> 代价：如果某个知识库文档把关键内容放在内嵌表格里，这部分内容会被跳过。这是一个**有意识的取舍**。

#### ⑦ `PDFParserConfig` 两个开关

```java
pdfConfig.setExtractInlineImages(false);
pdfConfig.setSortByPosition(true);
```

- **`setExtractInlineImages(false)`**：不提取行内图片。理由和 ⑥ 同源——PDF 里的图片转成文本是乱码级别的内容，对下游毫无价值，还白白增加开销。
- **`setSortByPosition(true)`**：**按 x/y 坐标排序输出文本**。这是 PDF 解析里非常关键的一项：PDF 本质是"把文字画在坐标上"，没有段落/行的概念。如果不排序，双栏排版（简历里的"左栏基本信息 + 右栏工作经历"）会被**按内容流顺序**输出，导致左右栏文字交错粘连，得到"姓名张三…2019-2022 腾讯…男 28 岁…负责后端开发"这种无法理解的文本。按坐标排序后，才能还原成人类阅读顺序。
  代码注释写的就是"改善多栏布局解析顺序"。

#### 入口的三种形态

`DocumentParseService` 对外暴露三个方法，覆盖三种数据来源：

| 方法 | 场景 |
|---|---|
| `parseContent(MultipartFile)` | 上传请求的同步路径，直接从请求体拿流 |
| `parseContent(byte[], fileName)` | 字节已在内存中（如从存储下载后） |
| `downloadAndParseContent(storageService, storageKey, name)` | **异步消费端**：只有 `storageKey`，需要先下载再解析 |

第三个方法的存在是**架构决策的直接产物**：简历分析、知识库向量化这些异步任务的消息体里**只带业务 ID 不带正文**，消费端必须回对象存储把原文件取回来重新解析。详见第五节。

---

### 4.6 第五步：文本清洗

`TextCleaningService.cleanText`（`infrastructure/file/TextCleaningService.java:80-105`）分两层、共 7 条规则：

**第一层：语义去噪**（5 条，全部是静态预编译的 `Pattern`）

| 规则 | 正则 | 为什么 |
|---|---|---|
| 控制字符 | `[\u0000-\u0008\u000B\u000C\u000E-\u001F]` | 剔除不可见字符，但**故意保留 `\n`(0x0A) 和 `\t`(0x09)**，因为换行和制表符是段落结构的一部分 |
| 图片文件名行 | `(?m)^image\d+\.(png\|jpe?g\|gif\|bmp\|webp)\s*$` | 这是 PDF/Tika 解析的典型副产物。**必须整行匹配**——注释里明确写了"防止误删正文中的文件名字符串"，否则正文里一句"图片管理模块支持 image1.png 上传"会被误伤 |
| 图片 URL | `https?://\S+?\.(png\|jpe?g\|gif\|bmp\|webp)(\?\S*)?` | 同上，去掉图片链接 |
| `file:` 协议路径 | `file:(//)?\S+` | Tika 处理 PDF 时产生的**本地临时文件路径**，绝对不能进语料 |
| 分隔线 | `(?m)^\s*[-_*=]{3,}\s*$` | `---`、`====`、`***` 这类装饰性分隔线，对语义无贡献 |

**第二层：格式规范化**（2 条）

```java
t = t.replace("\r\n", "\n").replace("\r", "\n");   // 统一换行符
t = t.replaceAll("(?m)[ \t]+$", "");               // 去行尾空白
t = t.replaceAll("\\n{3,}", "\n\n");               // 连续空行压缩为最多一个空行
return t.strip();
```

**为什么必须有这一层**：
- 换行符统一：Windows 上传的 `\r\n` 和 Linux 的 `\n` 混在一起，后续按行处理会出错。
- 去行尾空格：PDF 转文本常产生大量行尾空白，浪费 token。
- **压缩空行到"最多一个空行"而不是删除空行**：这是有意的——空行是**段落边界**。如果全删，简历里的"教育背景 / 工作经验 / 技能清单"会连成一片，LLM 的语义理解会明显变差；但保留太多空行又浪费 token，所以压到"1 个空行"这个刚好够表达段落分隔的程度。

**性能设计**：5 条正则都是 `static final Pattern`，**在类加载时编译一次**。`Pattern.compile` 是相对昂贵的操作，如果放在方法里每次都编译，在批量向量化场景下（一个文档几十上百个 chunk）会被放大。这是注释里写的"性能优化"。

> ⚠️ **不一致点**：第二层的两条规则用的是 `String.replaceAll(正则, ...)`，**每次调用都会重新编译正则**，和第一层"全部预编译"的做法不统一。小瑕疵，但面试时主动指出会显得读得细。

---

## 五、两个消费方的差异

同一套解析能力，简历和知识库的用法不同，差异恰好解释了链路里的几个设计：

| 维度 | 简历 | 知识库 |
|---|---|---|
| 大小限制 | 10MB | 50MB |
| 类型白名单 | 配置驱动（`app.allowed-types`） | 代码判断（额外支持 Markdown/RTF） |
| 解析文本是否落库 | **落库**（`resume_text` 列） | **不落库** |
| 解析时机 | 上传时解析一次 | 上传时解析一次（仅用于校验）+ 异步向量化时**再解析一次** |
| 重新处理时 | 优先复用库里的 `resume_text`，没有才回源解析 | 每次都回源解析 |

**简历侧**（`ResumeUploadService.java:81`）：

```java
String resumeText = parseService.parseResume(file);
if (resumeText == null || resumeText.trim().isEmpty()) {
    throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED,
        "无法从文件中提取文本内容，请确保文件不是扫描版PDF");
}
```

重新分析时（`ResumeUploadService.java:189-198`）**优先用库里缓存的文本**：

```java
String resumeText = source.resumeText();
boolean shouldCacheResumeText = !hasText(resumeText);
if (shouldCacheResumeText) {
    resumeText = parseService.downloadAndParseContent(source.storageKey(), source.originalFilename());
}
```

为什么：简历文本要反复用于**出题、评估、岗位匹配**多次，缓存起来避免重复解析；这个"优先用缓存、没有才回源"的写法还顺带兼容了**历史数据**（早期版本没存 `resume_text` 的记录）。

**知识库侧**（`KnowledgeBaseUploadService.java:67-71`）：

```java
String content = parseService.parseContent(file);
if (content == null || content.trim().isEmpty()) {
    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容，请确保文件格式正确");
}
```

上传时**同步解析一次**，但目的只是**校验"这份文档能不能提取出文本"**——不能，就当场拒绝，而不是进了异步队列再失败。解析出的 `content` 只用于返回 `contentLength` 给前端，**不落库**（向量化会产生分块和向量，原文没必要再存一份）。

真正的向量化发生在异步任务里（`KnowledgeVectorizationRabbitConsumer.java:75`）：

```java
String content = parseService.downloadAndParseContent(
        storageService, entity.getStorageKey(), entity.getOriginalFilename());
```

**为什么异步端要重新下载重新解析，而不是上传时就把文本放进消息？**
这是消息体设计原则的直接体现——**消息只带 `knowledgeBaseId`，不带文档正文**。好处是：
1. 消息体小，不占 MQ 磁盘、不受消息大小限制；
2. 简历/知识库文档含个人信息，尽量不落到 MQ 和死信表里；
3. 消费端读的是**当下最新的数据**，天然规避"消息里的内容已经过期"的问题；
4. 消息只依赖 ID，便于幂等和换实现（RabbitMQ / Redis Stream 双实现）。

代价就是**同一个文档会被解析两次**（上传校验 + 异步向量化）。这是一个明确的**用 CPU 换架构清晰度**的取舍。

---

## 六、对象存储 key 的生成

虽然不是 Tika 的事，但它是"回源解析"的前提（`FileStorageService.java:250-303`）：

```java
private String generateFileKey(String originalFilename, String prefix) {
    String datePath = now.format(DATE_PATH_FORMAT);          // yyyy/MM/dd
    String uuid = UUID.randomUUID().toString().substring(0, 8);
    String safeName = sanitizeFilename(originalFilename);     // 汉字转拼音
    return String.format("%s/%s/%s_%s", prefix, datePath, uuid, safeName);
}
```

最终形态：`resumes/2026/09/15/a1b2c3d4_ZhangSanJianLi.pdf`

**为什么要用 pinyin4j 把汉字转拼音**（`convertToPinyin:282-303`）：S3 的 object key 里放中文，会在 URL 编码、签名计算、跨系统日志、部分客户端的字符集处理上引入不确定性问题。转成拼音（且非字母数字字符统一替换为下划线）后 key 是**纯 ASCII 安全字符**，可预测、可调试。
同时保留 `uuid` 前缀和日期目录：前者防同名覆盖，后者便于按时间归档和清理。

---

## 七、错误处理策略

| 情况 | 处理 | 为什么 |
|---|---|---|
| 文件为空（size=0） | 返回 `""`，**不抛异常** | 由业务层决定语义：简历侧会 trim 后判断并给出"请确保文件不是扫描版 PDF"这种**有指导性的提示**，比底层抛"文件为空"体验好 |
| 校验失败（空选择/超限/类型不支持） | `BusinessException(BAD_REQUEST, ...)` | 用户错误，HTTP 语义上是 400 |
| IO / Tika / SAX 异常 | `BusinessException(INTERNAL_ERROR, "文件解析失败: ...")` | 符合项目规范 `AGENTS.md`：业务失败一律用 `BusinessException`，禁止 `RuntimeException`；异常对象作为**最后一个参数**传给日志 |
| 下载失败 / 空字节 | `BusinessException(INTERNAL_ERROR, "下载文件失败")` | 存储侧问题 |
| `downloadAndParseContent` 的异常包装 | 先 `catch (BusinessException e) { throw e; }` 再兜底 catch | **避免 BusinessException 被二次包装成"下载并解析文件失败: 下载文件失败"** 这种嵌套消息 |

---

## 八、测试覆盖

| 测试类 | 覆盖内容 |
|---|---|
| `DocumentParseServiceTest`（12 个用例） | TXT / Markdown 解析、字节数组入口、空文件返回空串、特殊字符、IO 异常转 BusinessException、中文简历、下载解析成功/失败/空字节、验证 `cleanText` 被调用、含 URL 文档、真实临时文件集成用例（**断言分隔线被清洗掉**） |
| `DocumentParseIntegrationTest`（`@Tag("integration")`） | 用**真实的** `DocumentParseService` + `TextCleaningService` 解析 classpath 下的 `test-files/sample-resume.txt`，端到端验证姓名、邮箱、教育背景等关键信息 |
| `TextCleaningServiceTest` | 清洗规则单测 |

测试风格符合项目规范：JUnit 5 + Mockito + AssertJ，`@DisplayName` 用中文描述意图。

值得注意的是 `DocumentParseServiceTest` 里对 `TextCleaningService` 的处理方式——**mock 掉并让它原样返回输入**（`setUp` 里的 `thenAnswer(invocation -> invocation.getArgument(0))`），这样单测聚焦在"解析"本身；而"解析 + 清洗"的协作由 `DocumentParseIntegrationTest` 用真实实例覆盖。**单测隔离、集成测联通**，分工很清楚。

---

## 九、设计权衡与已知不足

按"面试官可能挑战"的顺序列出，每条都对应代码事实：

1. **扫描版 PDF 无解**。纯图片的 PDF 没有文本层，Tika 提取结果为空，只能报"请确保文件不是扫描版 PDF"。没有接 OCR。
2. **5MB 是硬闸门而非截断**（见 4.5-②）。超过就整体失败，对超长知识库文档不友好——更合理的策略可能是"截断 + 明确告知用户被截断"或"分段流式解析"。
3. **哈希计算把整个文件读进内存**（`file.getBytes()`）。流式版本 `calculateHash(InputStream)` 已实现但**无人调用**，50MB 文件并发上传时内存压力明显。
4. **同一个知识库文档被解析两次**（上传校验一次、异步向量化一次），CPU 换架构清晰度。缓解方向：上传时把文本缓存到 Redis/临时存储。
5. **解析在同步请求线程上执行**。大文件会拉长上传接口的响应时间；好处是能立刻告诉用户"这个文件解析不出内容"。
6. **清洗规则一半预编译、一半内联**（4.6 末），不统一。
7. **两套类型白名单策略不一致**（配置驱动 vs 代码硬编码），维护时容易漏改一边。
8. **`ContentTypeDetectionService` 直接 `new Tika()`**，而不是注册成 Spring Bean 注入。虽然复用了实例，但**不可替换、不好 mock**，和项目里其他服务统一用构造器注入的风格不一致。
9. **没有病毒扫描**，也没有对文件做更深的合法性校验（如 PDF 结构校验）。
10. **禁用嵌入资源是有损的**：内嵌表格等有可能承载有效信息的内容会被一并跳过。

---

## 十、面试问答速查

**Q1：为什么选 Tika，而不是直接用 PDFBox + POI？**
一套 API 覆盖多格式，`AutoDetectParser` 按魔数自动分发，新增格式基本不用改业务代码；省掉了自己写"按类型分发解析器"的逻辑和多个底层库的版本维护。代价是依赖包较大（`tika-parsers-standard-package` 带了一堆解析器）。

**Q2：怎么判断上传文件类型？为什么不用 `Content-Type` 请求头？**
用 `Tika.detect(InputStream, fileName)` 基于**内容魔数**判断，文件名只作**提示**（纯文本/Markdown 没有魔数，需要文件名辅助）。请求头是客户端可控的，伪造成本低；内容检测是文件自证。检测失败时降级用请求头，但后面还有白名单兜底——**检测放宽、校验收紧**。

**Q3：解析大文件会不会 OOM？超长文档怎么处理？**
`BodyContentHandler` 设了 5MB 写入上限做保护。注意：**超限不是静默截断，而是抛 `WriteLimitReachedException`（SAXException 子类），最终转成"文件解析失败"业务错误**——这是 fail-fast 策略，宁可明确失败也不给出不完整的文本。这个行为我反编译 tika-core 2.9.2 确认过（`WriteOutContentHandler` 的 `throwOnWriteLimitReached` 默认为 true）。

**Q4：为什么要禁用嵌入文档解析？**
避免图片/附件被递归解析产生二进制噪声和磁盘临时文件，也避免嵌套资源导致的解析开销失控。代价是内嵌表格等内容会被跳过，这是有意的取舍。

**Q5：PDF 解析出来的文本顺序混乱怎么办？**
`PDFParserConfig.setSortByPosition(true)` 按字符 x/y 坐标排序，解决双栏排版的阅读顺序问题。同时 `setExtractInlineImages(false)` 关掉图片提取。

**Q6：文本清洗为什么要两层？为什么图片文件名要整行匹配？**
第一层做**语义去噪**（控制字符、图片名/链接、`file:` 临时路径、分隔线），第二层做**格式规范化**（换行统一、行尾空白、空行压缩）。分两层是因为它们的性质不同：前者是"去掉不该有的东西"，后者是"把该有的东西规整好"。
图片名整行匹配（`^image\d+\.(png|...)$`）是为了**避免误删正文**——否则"支持 image1.png 上传"这种正常句子会被破坏。

**Q7：简历和知识库怎么共用这套解析能力？**
两者各有一个薄封装 `ResumeParseService` / `KnowledgeBaseParseService` 委托到 `DocumentParseService`，业务侧看不到 Tika。差异（大小限制、白名单、是否落库）留在各自的业务服务里，基础设施层保持格式无关。

**Q8：异步任务里为什么还要重新下载解析？**
因为消息体**只带 `knowledgeBaseId` 不带正文**。这样消息小、不落 PII 到 MQ、消费端读的是最新数据、便于幂等和双实现切换。代价是同一文档解析两次。

**Q9：这段代码你觉得哪里可以改？**
优先说这三条：① 哈希用流式（现成方法没人调）；② 5MB 上限从"失败"改成"截断 + 提示"或分段解析；③ 清洗正则统一预编译。再补一句一致性：两套白名单策略应该收敛。

---

## 附：涉及文件清单

| 文件 | 说明 |
|---|---|
| `app/src/main/java/interview/guide/infrastructure/file/DocumentParseService.java` | 核心解析 |
| `app/src/main/java/interview/guide/infrastructure/file/ContentTypeDetectionService.java` | MIME 检测 |
| `app/src/main/java/interview/guide/infrastructure/file/FileValidationService.java` | 空/大小/白名单校验 |
| `app/src/main/java/interview/guide/infrastructure/file/FileHashService.java` | SHA-256 指纹 |
| `app/src/main/java/interview/guide/infrastructure/file/NoOpEmbeddedDocumentExtractor.java` | 禁用嵌入资源 |
| `app/src/main/java/interview/guide/infrastructure/file/TextCleaningService.java` | 文本清洗 |
| `app/src/main/java/interview/guide/infrastructure/file/FileStorageService.java` | 对象存储 + key 生成 |
| `app/src/main/java/interview/guide/modules/resume/service/ResumeParseService.java` | 简历侧委托 |
| `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseParseService.java` | 知识库侧委托 |
| `app/src/main/java/interview/guide/modules/resume/service/ResumeUploadService.java` | 简历上传编排 |
| `app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java` | 知识库上传编排 |
| `app/src/test/java/interview/guide/infrastructure/file/DocumentParseServiceTest.java` | 单元测试 |
| `app/src/test/java/interview/guide/infrastructure/file/DocumentParseIntegrationTest.java` | 集成测试 |
