# 文件阅览组件完整示例

本文档提供一个完整的文件阅览组件示例，包含前端实现、后端 Action 实现、元数据定义和集成配置。

---

## 1. 组件概述

### 功能特性

- **多格式支持**：图片、PDF、Word、Excel、PPT、视频、音频
- **自动检测**：根据文件扩展名自动选择阅览器
- **安全功能**：水印、防截屏、权限控制
- **工具栏**：下载、打印、缩放、旋转
- **分页浏览**：PDF/文档分页显示
- **缩略图预览**：图片/PDF 缩略图导航

### 技术栈

- **前端**：React + TypeScript + PDF.js + Mammoth.js
- **后端**：Java + Spring Boot + 对象存储 (MinIO/OSS)
- **协议**：HTTP/REST

---

## 2. 后端实现

### 2.1 文件资源定义 (FileResource.java)

```java
package com.example.storage.action;

import com.example.action.sdk.annotation.*;
import com.example.storage.service.FileService;
import com.example.storage.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.util.List;

/**
 * 文件管理资源
 * 提供文件的上传、下载、预览、删除等功能
 */
@ActionResource(
    value = "file",
    description = "文件管理",
    namespace = "storage"
)
@Component
public class FileResource {

    @Autowired
    private FileService fileService;

    @Autowired
    private FileSecurityService securityService;

    @Autowired
    private WatermarkService watermarkService;

    /**
     * 获取文件信息
     *
     * @param fileId 文件ID
     * @return 文件详细信息
     */
    @Action(
        value = "get",
        title = "获取文件信息",
        description = "根据文件ID获取文件的元数据和访问信息",
        async = false,
        timeout = 5000
    )
    public FileInfo getFile(
            @ActionParam(
                value = "fileId",
                description = "文件唯一标识",
                required = true,
                validation = @ValidationRule(pattern = "^[a-zA-Z0-9_-]{10,50}$")
            ) String fileId,
            @ActionParam(
                value = "includePreviewUrl",
                description = "是否包含预览URL",
                required = false,
                defaultValue = "false"
            ) boolean includePreviewUrl) {

        // 校验文件访问权限
        securityService.checkPermission(fileId, FilePermission.VIEW);

        // 获取文件信息
        FileInfo fileInfo = fileService.getFileInfo(fileId);

        if (includePreviewUrl) {
            // 生成带签名的预览URL
            String previewUrl = fileService.generateSignedUrl(fileId, 3600);
            fileInfo.setPreviewUrl(previewUrl);
        }

        return fileInfo;
    }

    /**
     * 获取预览URL
     *
     * @param fileId 文件ID
     * @param expiresIn URL过期时间(秒)
     * @param watermarkConfig 水印配置
     * @return 预览URL信息
     */
    @Action(
        value = "preview-url",
        title = "获取预览URL",
        description = "生成带安全水印的预览URL",
        async = false,
        timeout = 3000
    )
    public PreviewUrlInfo getPreviewUrl(
            @ActionParam(value = "fileId", required = true) String fileId,
            @ActionParam(value = "expiresIn", defaultValue = "3600") int expiresIn,
            @ActionParam(value = "watermark", required = false) WatermarkConfig watermarkConfig) {

        // 权限检查
        securityService.checkPermission(fileId, FilePermission.PREVIEW);

        // 获取当前用户信息用于水印
        UserInfo currentUser = securityService.getCurrentUser();

        // 如果启用水印但未配置，使用默认配置
        if (watermarkConfig == null) {
            watermarkConfig = WatermarkConfig.builder()
                .enabled(true)
                .text(currentUser.getUsername() + " " + currentUser.getUserId())
                .opacity(0.15)
                .fontSize(14)
                .color("#FF0000")
                .rotation(45)
                .density("normal")
                .build();
        }

        // 生成带水印的预览URL
        String previewUrl = fileService.generatePreviewUrl(
            fileId,
            expiresIn,
            watermarkConfig
        );

        return PreviewUrlInfo.builder()
            .url(previewUrl)
            .expiresAt(System.currentTimeMillis() + expiresIn * 1000)
            .fileType(fileService.detectFileType(fileId))
            .viewerType(determineViewerType(fileId))
            .watermarkEnabled(watermarkConfig.isEnabled())
            .build();
    }

    /**
     * 下载文件
     *
     * @param fileId 文件ID
     * @param response HTTP响应
     */
    @Action(
        value = "download",
        title = "下载文件",
        description = "下载原始文件",
        async = true,
        timeout = 60000
    )
    public FileDownloadResult downloadFile(
            @ActionParam(value = "fileId", required = true) String fileId,
            @ActionParam(value = "filename", required = false) String filename,
            HttpServletResponse response) {

        // 权限检查
        securityService.checkPermission(fileId, FilePermission.DOWNLOAD);

        // 记录下载审计日志
        auditLog.record(AuditAction.FILE_DOWNLOAD, fileId);

        // 获取文件流
        InputStream fileStream = fileService.getFileStream(fileId);
        FileInfo fileInfo = fileService.getFileInfo(fileId);

        // 设置响应头
        String downloadName = filename != null ? filename : fileInfo.getOriginalName();
        response.setContentType(fileInfo.getContentType());
        response.setHeader("Content-Disposition",
            "attachment; filename=\"" + encodeFilename(downloadName) + "\"");

        // 流式传输
        try {
            fileStream.transferTo(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (Exception e) {
            throw new FileOperationException("文件下载失败", e);
        }

        return FileDownloadResult.builder()
            .fileId(fileId)
            .filename(downloadName)
            .size(fileInfo.getSize())
            .success(true)
            .build();
    }

    /**
     * 获取文件内容（用于在线预览）
     *
     * @param fileId 文件ID
     * @param pageNumber 页码（PDF/文档）
     * @param pageSize 每页大小
     * @return 文件内容
     */
    @Action(
        value = "content",
        title = "获取文件内容",
        description = "获取文件的可预览内容，支持分页",
        async = false,
        timeout = 10000
    )
    public FileContent getFileContent(
            @ActionParam(value = "fileId", required = true) String fileId,
            @ActionParam(value = "pageNumber", defaultValue = "1") int pageNumber,
            @ActionParam(value = "pageSize", defaultValue = "10") int pageSize,
            @ActionParam(value = "convertFormat", required = false) String convertFormat) {

        securityService.checkPermission(fileId, FilePermission.VIEW);

        FileInfo fileInfo = fileService.getFileInfo(fileId);
        FileType fileType = fileInfo.getFileType();

        // 根据文件类型选择处理方式
        switch (fileType) {
            case PDF:
                return extractPdfContent(fileId, pageNumber, pageSize);
            case WORD:
            case EXCEL:
            case PPT:
                return extractOfficeContent(fileId, pageNumber, pageSize, convertFormat);
            case TEXT:
            case CODE:
                return extractTextContent(fileId);
            default:
                throw new UnsupportedOperationException("不支持的文件类型: " + fileType);
        }
    }

    /**
     * 获取缩略图
     *
     * @param fileId 文件ID
     * @param width 宽度
     * @param height 高度
     * @return 缩略图信息
     */
    @Action(
        value = "thumbnail",
        title = "获取缩略图",
        description = "生成文件缩略图",
        async = false,
        timeout = 5000
    )
    public ThumbnailInfo getThumbnail(
            @ActionParam(value = "fileId", required = true) String fileId,
            @ActionParam(value = "width", defaultValue = "200") int width,
            @ActionParam(value = "height", defaultValue = "200") int height) {

        securityService.checkPermission(fileId, FilePermission.VIEW);

        String thumbnailUrl = fileService.generateThumbnail(fileId, width, height);

        return ThumbnailInfo.builder()
            .url(thumbnailUrl)
            .width(width)
            .height(height)
            .build();
    }

    /**
     * 搜索文件
     */
    @Action(
        value = "search",
        title = "搜索文件",
        description = "按名称、类型等条件搜索文件"
    )
    public PageResult<FileInfo> searchFiles(
            @ActionParam(value = "keyword", required = false) String keyword,
            @ActionParam(value = "fileTypes", required = false) List<String> fileTypes,
            @ActionParam(value = "page", defaultValue = "1") int page,
            @ActionParam(value = "pageSize", defaultValue = "20") int pageSize) {

        FileSearchParam searchParam = FileSearchParam.builder()
            .keyword(keyword)
            .fileTypes(fileTypes)
            .page(page)
            .pageSize(pageSize)
            .build();

        return fileService.searchFiles(searchParam);
    }

    /**
     * 批量获取文件信息
     */
    @Action(
        value = "batch-get",
        title = "批量获取文件信息",
        description = "批量查询多个文件的信息"
    )
    public List<FileInfo> batchGetFiles(
            @ActionParam(value = "fileIds", required = true) List<String> fileIds) {
        return fileIds.stream()
            .filter(id -> securityService.hasPermission(id, FilePermission.VIEW))
            .map(fileService::getFileInfo)
            .collect(Collectors.toList());
    }

    // 私有辅助方法

    private ViewerType determineViewerType(String fileId) {
        FileType fileType = fileService.detectFileType(fileId);
        switch (fileType) {
            case IMAGE:
                return ViewerType.IMAGE;
            case PDF:
                return ViewerType.PDF;
            case VIDEO:
                return ViewerType.VIDEO;
            case AUDIO:
                return ViewerType.AUDIO;
            case WORD:
            case EXCEL:
            case PPT:
                return ViewerType.DOCUMENT;
            default:
                return ViewerType.GENERIC;
        }
    }

    private FileContent extractPdfContent(String fileId, int pageNumber, int pageSize) {
        // 使用 PDFBox 提取 PDF 内容
        return fileService.extractPdfContent(fileId, pageNumber, pageSize);
    }

    private FileContent extractOfficeContent(String fileId, int pageNumber, int pageSize, String convertFormat) {
        // 使用 LibreOffice 或 Office Online Server 转换
        return fileService.convertOfficeDocument(fileId, pageNumber, pageSize, convertFormat);
    }

    private FileContent extractTextContent(String fileId) {
        return fileService.readTextFile(fileId);
    }

    private String encodeFilename(String filename) {
        try {
            return URLEncoder.encode(filename, "UTF-8").replaceAll("\\+", "%20");
        } catch (Exception e) {
            return filename;
        }
    }
}
```

### 2.2 数据传输对象 (DTO)

```java
// FileInfo.java
@Data
@Builder
public class FileInfo {
    private String fileId;
    private String originalName;
    private String storageName;
    private Long size;
    private String contentType;
    private FileType fileType;
    private String extension;
    private String previewUrl;
    private String thumbnailUrl;
    private Integer pageCount;          // PDF/文档页数
    private Long duration;              // 视频/音频时长
    private Dimension imageDimension;   // 图片尺寸
    private String ownerId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Map<String, Object> metadata;
}

// PreviewUrlInfo.java
@Data
@Builder
public class PreviewUrlInfo {
    private String url;
    private Long expiresAt;
    private FileType fileType;
    private ViewerType viewerType;
    private boolean watermarkEnabled;
    private Map<String, Object> securityHeaders;
}

// WatermarkConfig.java
@Data
@Builder
public class WatermarkConfig {
    private boolean enabled;
    private String text;
    private Double opacity;
    private Integer fontSize;
    private String color;
    private Integer rotation;
    private String density;     // sparse, normal, dense
    private String position;    // center, tile
}

// FileContent.java
@Data
@Builder
public class FileContent {
    private String fileId;
    private FileType fileType;
    private String content;         // 文本内容或HTML
    private Integer currentPage;
    private Integer totalPages;
    private Boolean hasMore;
    private List<PageInfo> pages;   // 分页信息
    private String downloadUrl;     // 原始文件下载
}

// ThumbnailInfo.java
@Data
@Builder
public class ThumbnailInfo {
    private String url;
    private Integer width;
    private Integer height;
    private String format;
}

// FileSearchParam.java
@Data
@Builder
public class FileSearchParam {
    private String keyword;
    private List<String> fileTypes;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String ownerId;
    private int page;
    private int pageSize;
}

// 枚举定义
public enum FileType {
    IMAGE("图片", "image/*"),
    PDF("PDF", "application/pdf"),
    WORD("Word", "application/msword"),
    EXCEL("Excel", "application/vnd.ms-excel"),
    PPT("PPT", "application/vnd.ms-powerpoint"),
    VIDEO("视频", "video/*"),
    AUDIO("音频", "audio/*"),
    TEXT("文本", "text/plain"),
    CODE("代码", "text/*"),
    ARCHIVE("压缩包", "application/zip"),
    OTHER("其他", "application/octet-stream");

    private final String displayName;
    private final String mimePattern;
}

public enum ViewerType {
    IMAGE,      // 图片查看器
    PDF,        // PDF查看器
    DOCUMENT,   // 文档查看器
    VIDEO,      // 视频播放器
    AUDIO,      // 音频播放器
    CODE,       // 代码编辑器
    GENERIC     // 通用下载
}

public enum FilePermission {
    VIEW,       // 查看
    PREVIEW,    // 预览
    DOWNLOAD,   // 下载
    EDIT,       // 编辑
    DELETE,     // 删除
    SHARE       // 分享
}
```

### 2.3 文件服务实现

```java
@Service
public class FileServiceImpl implements FileService {

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private CacheManager cacheManager;

    @Value("${storage.bucket-name}")
    private String bucketName;

    @Override
    public FileInfo getFileInfo(String fileId) {
        // 先查缓存
        FileInfo cached = cacheManager.get("file:info:" + fileId, FileInfo.class);
        if (cached != null) {
            return cached;
        }

        // 查数据库
        FileEntity entity = fileRepository.findByFileId(fileId)
            .orElseThrow(() -> new FileNotFoundException("文件不存在: " + fileId));

        FileInfo info = convertToFileInfo(entity);

        // 写入缓存
        cacheManager.put("file:info:" + fileId, info, 3600);

        return info;
    }

    @Override
    public String generateSignedUrl(String fileId, int expiresIn) {
        try {
            return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(fileId)
                    .expiry(expiresIn)
                    .build()
            );
        } catch (Exception e) {
            throw new FileOperationException("生成签名URL失败", e);
        }
    }

    @Override
    public String generatePreviewUrl(String fileId, int expiresIn, WatermarkConfig watermarkConfig) {
        // 生成预览 URL，可包含水印参数
        String baseUrl = generateSignedUrl(fileId, expiresIn);

        if (watermarkConfig != null && watermarkConfig.isEnabled()) {
            // 添加水印服务处理
            return watermarkService.addWatermarkProxy(baseUrl, watermarkConfig);
        }

        return baseUrl;
    }

    @Override
    public InputStream getFileStream(String fileId) {
        try {
            return minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(fileId)
                    .build()
            );
        } catch (Exception e) {
            throw new FileOperationException("获取文件流失败", e);
        }
    }

    @Override
    public FileContent extractPdfContent(String fileId, int pageNumber, int pageSize) {
        try (InputStream is = getFileStream(fileId);
             PDDocument document = PDDocument.load(is)) {

            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();

            // 计算起始和结束页
            int startPage = (pageNumber - 1) * pageSize + 1;
            int endPage = Math.min(startPage + pageSize - 1, totalPages);

            stripper.setStartPage(startPage);
            stripper.setEndPage(endPage);

            String text = stripper.getText(document);

            // 生成每页的预览图片
            List<PageInfo> pages = new ArrayList<>();
            for (int i = startPage; i <= endPage; i++) {
                pages.add(PageInfo.builder()
                    .pageNumber(i)
                    .imageUrl(generatePageImage(fileId, i))
                    .build());
            }

            return FileContent.builder()
                .fileId(fileId)
                .fileType(FileType.PDF)
                .content(text)
                .currentPage(pageNumber)
                .totalPages(totalPages)
                .hasMore(endPage < totalPages)
                .pages(pages)
                .build();

        } catch (Exception e) {
            throw new FileOperationException("PDF内容提取失败", e);
        }
    }

    @Override
    public FileType detectFileType(String fileId) {
        FileInfo info = getFileInfo(fileId);
        return info.getFileType();
    }

    @Override
    public String generateThumbnail(String fileId, int width, int height) {
        FileType fileType = detectFileType(fileId);
        String cacheKey = String.format("thumbnail:%s:%dx%d", fileId, width, height);

        // 查缓存
        String cached = cacheManager.get(cacheKey, String.class);
        if (cached != null) {
            return cached;
        }

        String thumbnailUrl;

        switch (fileType) {
            case IMAGE:
                thumbnailUrl = generateImageThumbnail(fileId, width, height);
                break;
            case PDF:
                thumbnailUrl = generatePdfThumbnail(fileId, width, height);
                break;
            case VIDEO:
                thumbnailUrl = generateVideoThumbnail(fileId, width, height);
                break;
            default:
                // 使用默认图标
                thumbnailUrl = getDefaultIcon(fileType);
        }

        // 写入缓存
        cacheManager.put(cacheKey, thumbnailUrl, 86400);

        return thumbnailUrl;
    }

    private String generateImageThumbnail(String fileId, int width, int height) {
        // 使用 Thumbnailator 生成缩略图
        try (InputStream is = getFileStream(fileId);
             ByteArrayOutputStream os = new ByteArrayOutputStream()) {

            Thumbnails.of(is)
                .size(width, height)
                .outputFormat("jpg")
                .toOutputStream(os);

            // 上传到临时存储并返回URL
            return uploadThumbnail(fileId + "_thumb.jpg", os.toByteArray());

        } catch (Exception e) {
            throw new FileOperationException("生成图片缩略图失败", e);
        }
    }

    private String generatePdfThumbnail(String fileId, int width, int height) {
        // 提取PDF第一页作为缩略图
        try (InputStream is = getFileStream(fileId);
             PDDocument document = PDDocument.load(is)) {

            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, 72);

            // 调整大小
            BufferedImage thumbnail = Thumbnails.of(image)
                .size(width, height)
                .asBufferedImage();

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "jpg", os);

            return uploadThumbnail(fileId + "_thumb.jpg", os.toByteArray());

        } catch (Exception e) {
            throw new FileOperationException("生成PDF缩略图失败", e);
        }
    }

    private String uploadThumbnail(String objectName, byte[] data) {
        try {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object("thumbnails/" + objectName)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType("image/jpeg")
                    .build()
            );

            return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object("thumbnails/" + objectName)
                    .expiry(86400)
                    .build()
            );
        } catch (Exception e) {
            throw new FileOperationException("上传缩略图失败", e);
        }
    }
}
```

### 2.4 Action 元数据定义

```yaml
# storage.file.get.metadata.yml
apiVersion: action.example.io/v1
kind: Action
metadata:
  name: get
  namespace: storage.file
  version: 1.0.0
  title: 获取文件信息
  description: 根据文件ID获取文件的元数据和访问信息
  labels:
    category: storage
    component: file-viewer
    visibility: public
  owner: storage-team
spec:
  type: SYNC
  protocol: HTTP
  endpoint:
    service: storage-service
    path: /api/storage/file/get
    method: POST
  inputSchema:
    type: object
    required:
      - fileId
    properties:
      fileId:
        type: string
        description: 文件唯一标识
        pattern: ^[a-zA-Z0-9_-]{10,50}$
      includePreviewUrl:
        type: boolean
        description: 是否包含预览URL
        default: false
  outputSchema:
    type: object
    properties:
      fileId:
        type: string
      originalName:
        type: string
      size:
        type: integer
        format: int64
      contentType:
        type: string
      fileType:
        type: string
        enum: [IMAGE, PDF, WORD, EXCEL, PPT, VIDEO, AUDIO, TEXT]
      previewUrl:
        type: string
        format: uri
      thumbnailUrl:
        type: string
        format: uri
      pageCount:
        type: integer
      duration:
        type: integer
      createTime:
        type: string
        format: date-time
  errorSchema:
    type: object
    properties:
      code:
        type: string
        enum: [FILE_NOT_FOUND, PERMISSION_DENIED, INVALID_FILE_ID]
      message:
        type: string
  timeoutMs: 5000
  retryPolicy:
    maxAttempts: 2
    backoffMs: 100
    retryableCodes: [TIMEOUT, SERVICE_UNAVAILABLE]
  auth:
    mode: JWT
    scopes: [file:read]
  idempotency:
    enabled: true
    keyHeader: X-Idempotency-Key
    ttlSeconds: 3600
status:
  state: ACTIVE
  lastUpdatedAt: 2024-01-15T10:30:00Z
```

```yaml
# storage.file.preview-url.metadata.yml
apiVersion: action.example.io/v1
kind: Action
metadata:
  name: preview-url
  namespace: storage.file
  version: 1.0.0
  title: 获取预览URL
  description: 生成带安全水印的预览URL
  labels:
    category: storage
    component: file-viewer
  owner: storage-team
spec:
  type: SYNC
  protocol: HTTP
  endpoint:
    service: storage-service
    path: /api/storage/file/preview-url
    method: POST
  inputSchema:
    type: object
    required:
      - fileId
    properties:
      fileId:
        type: string
      expiresIn:
        type: integer
        description: URL过期时间(秒)
        default: 3600
        minimum: 60
        maximum: 86400
      watermark:
        type: object
        properties:
          enabled:
            type: boolean
            default: true
          text:
            type: string
          opacity:
            type: number
            minimum: 0
            maximum: 1
            default: 0.15
          fontSize:
            type: integer
            default: 14
          color:
            type: string
            default: "#FF0000"
          rotation:
            type: integer
            default: 45
  outputSchema:
    type: object
    properties:
      url:
        type: string
        format: uri
      expiresAt:
        type: integer
        format: int64
      fileType:
        type: string
      viewerType:
        type: string
        enum: [IMAGE, PDF, DOCUMENT, VIDEO, AUDIO, GENERIC]
      watermarkEnabled:
        type: boolean
  timeoutMs: 3000
  auth:
    mode: JWT
    scopes: [file:preview]
status:
  state: ACTIVE
  lastUpdatedAt: 2024-01-15T10:30:00Z
```

---

## 3. 前端实现

### 3.1 组件定义 (FileViewer.definition.ts)

```typescript
import { ComponentDefinition } from '@lowcode/sdk';

export const FileViewerDefinition: ComponentDefinition = {
  id: 'data.file-viewer',
  name: 'FileViewer',
  version: '1.2.0',
  title: '文件阅览器',
  description: '支持多格式文件的在线预览，包括图片、PDF、Office文档、音视频等',
  icon: 'FileSearchOutlined',
  category: 'DATA',
  tags: ['file', 'preview', 'pdf', 'image', 'document'],

  entry: {
    type: 'esm',
    url: '/components/file-viewer/index.js',
    cssUrl: '/components/file-viewer/index.css'
  },

  // Props 配置 Schema
  propsSchema: {
    type: 'object',
    title: '文件阅览器配置',
    properties: {
      // 数据源
      fileId: {
        type: 'string',
        title: '文件ID',
        description: '文件的唯一标识',
        'x-component': 'Input',
        'x-component-props': {
          placeholder: '请输入文件ID'
        }
      },
      fileUrl: {
        type: 'string',
        title: '文件URL',
        description: '文件的直接访问URL（优先级低于fileId）',
        'x-component': 'Input',
        'x-component-props': {
          placeholder: 'https://...'
        }
      },

      // 显示配置
      viewerType: {
        type: 'string',
        title: '阅览器类型',
        enum: ['auto', 'image', 'pdf', 'document', 'video', 'audio'],
        default: 'auto',
        'x-component': 'Select',
        'x-component-props': {
          options: [
            { label: '自动检测', value: 'auto' },
            { label: '图片查看器', value: 'image' },
            { label: 'PDF查看器', value: 'pdf' },
            { label: '文档查看器', value: 'document' },
            { label: '视频播放器', value: 'video' },
            { label: '音频播放器', value: 'audio' }
          ]
        }
      },

      // 外观配置
      width: {
        type: 'string',
        title: '宽度',
        default: '100%',
        'x-component': 'Input'
      },
      height: {
        type: 'string',
        title: '高度',
        default: '500px',
        'x-component': 'Input'
      },
      theme: {
        type: 'string',
        title: '主题',
        enum: ['light', 'dark'],
        default: 'light',
        'x-component': 'Radio.Group',
        'x-component-props': {
          optionType: 'button',
          options: [
            { label: '浅色', value: 'light' },
            { label: '深色', value: 'dark' }
          ]
        }
      },

      // 工具栏配置
      showToolbar: {
        type: 'boolean',
        title: '显示工具栏',
        default: true,
        'x-component': 'Switch'
      },
      toolbarItems: {
        type: 'array',
        title: '工具栏项',
        default: ['zoom', 'rotate', 'download', 'print', 'fullscreen'],
        'x-component': 'Checkbox.Group',
        'x-component-props': {
          options: [
            { label: '缩放', value: 'zoom' },
            { label: '旋转', value: 'rotate' },
            { label: '下载', value: 'download' },
            { label: '打印', value: 'print' },
            { label: '全屏', value: 'fullscreen' }
          ]
        },
        'x-reactions': [
          {
            dependencies: ['showToolbar'],
            fulfill: {
              state: {
                visible: '{{$deps[0]}}'
              }
            }
          }
        ]
      },

      // 功能开关
      allowDownload: {
        type: 'boolean',
        title: '允许下载',
        default: false,
        'x-component': 'Switch'
      },
      allowPrint: {
        type: 'boolean',
        title: '允许打印',
        default: false,
        'x-component': 'Switch'
      },
      allowFullscreen: {
        type: 'boolean',
        title: '允许全屏',
        default: true,
        'x-component': 'Switch'
      },

      // 水印配置
      watermark: {
        type: 'object',
        title: '水印配置',
        properties: {
          enabled: {
            type: 'boolean',
            title: '启用水印',
            default: false,
            'x-component': 'Switch'
          },
          text: {
            type: 'string',
            title: '水印文字',
            description: '支持变量: {username}, {userId}, {datetime}',
            'x-component': 'Input',
            'x-reactions': [
              {
                dependencies: ['watermark.enabled'],
                fulfill: {
                  state: {
                    visible: '{{$deps[0]}}'
                  }
                }
              }
            ]
          },
          opacity: {
            type: 'number',
            title: '透明度',
            default: 0.15,
            minimum: 0,
            maximum: 1,
            'x-component': 'Slider',
            'x-component-props': {
              step: 0.05
            },
            'x-reactions': [
              {
                dependencies: ['watermark.enabled'],
                fulfill: {
                  state: {
                    visible: '{{$deps[0]}}'
                  }
                }
              }
            ]
          },
          fontSize: {
            type: 'number',
            title: '字体大小',
            default: 14,
            'x-component': 'InputNumber',
            'x-reactions': [
              {
                dependencies: ['watermark.enabled'],
                fulfill: {
                  state: {
                    visible: '{{$deps[0]}}'
                  }
                }
              }
            ]
          },
          color: {
            type: 'string',
            title: '颜色',
            default: '#FF0000',
            'x-component': 'ColorPicker',
            'x-reactions': [
              {
                dependencies: ['watermark.enabled'],
                fulfill: {
                  state: {
                    visible: '{{$deps[0]}}'
                  }
                }
              }
            ]
          }
        }
      },

      // 高级配置
      lazyLoad: {
        type: 'boolean',
        title: '懒加载',
        description: '进入视口后再加载文件',
        default: true,
        'x-component': 'Switch'
      },
      cacheEnabled: {
        type: 'boolean',
        title: '启用缓存',
        default: true,
        'x-component': 'Switch'
      },
      errorFallback: {
        type: 'string',
        title: '错误提示',
        default: '文件加载失败，请重试',
        'x-component': 'Input.TextArea'
      }
    }
  },

  // 事件定义
  eventsSchema: [
    {
      name: 'onLoad',
      title: '文件加载完成',
      description: '文件内容加载完成后触发',
      parameters: [
        { name: 'fileInfo', type: 'FileInfo', description: '文件信息对象' }
      ]
    },
    {
      name: 'onError',
      title: '加载失败',
      description: '文件加载失败时触发',
      parameters: [
        { name: 'error', type: 'Error', description: '错误对象' },
        { name: 'errorCode', type: 'string', description: '错误代码' }
      ]
    },
    {
      name: 'onDownload',
      title: '下载文件',
      description: '用户点击下载时触发',
      parameters: [
        { name: 'fileInfo', type: 'FileInfo', description: '文件信息' }
      ]
    },
    {
      name: 'onPageChange',
      title: '页码变更',
      description: 'PDF/文档切换页码时触发',
      parameters: [
        { name: 'currentPage', type: 'number', description: '当前页码' },
        { name: 'totalPages', type: 'number', description: '总页数' }
      ]
    },
    {
      name: 'onZoomChange',
      title: '缩放变更',
      description: '缩放比例变更时触发',
      parameters: [
        { name: 'zoom', type: 'number', description: '当前缩放比例' }
      ]
    },
    {
      name: 'onRotate',
      title: '旋转',
      description: '图片/PDF旋转时触发',
      parameters: [
        { name: 'rotation', type: 'number', description: '旋转角度' }
      ]
    }
  ],

  // 关联后端 Action
  actions: [
    {
      name: 'loadFile',
      displayName: '加载文件',
      description: '根据fileId加载文件信息',
      actionKey: 'storage.file.get:1.0.0',
      trigger: 'auto',
      inputMapping: {
        fileId: { type: 'prop', propName: 'fileId' },
        includePreviewUrl: { type: 'static', value: true }
      },
      outputMapping: {
        fileInfo: { type: 'direct', resultPath: 'data' }
      },
      loadingTarget: 'loading',
      errorTarget: 'error'
    },
    {
      name: 'getPreviewUrl',
      displayName: '获取预览URL',
      description: '获取带水印的预览URL',
      actionKey: 'storage.file.preview-url:1.0.0',
      trigger: 'auto',
      inputMapping: {
        fileId: { type: 'prop', propName: 'fileId' },
        watermark: { type: 'prop', propName: 'watermark' }
      },
      outputMapping: {
        previewUrl: { type: 'direct', resultPath: 'data.url' },
        viewerType: { type: 'direct', resultPath: 'data.viewerType' }
      }
    },
    {
      name: 'download',
      displayName: '下载文件',
      description: '下载原始文件',
      actionKey: 'storage.file.download:1.0.0',
      trigger: 'manual',
      inputMapping: {
        fileId: { type: 'prop', propName: 'fileId' }
      }
    }
  ],

  // 设计时配置
  designTime: {
    resizable: true,
    draggable: true,
    defaultWidth: 600,
    defaultHeight: 400,
    minimizable: true,
    maximizable: true
  },

  // 依赖
  dependencies: [
    { name: 'pdfjs-dist', version: '^3.0.0' },
    { name: 'mammoth', version: '^1.6.0' },
    { name: 'react-pdf', version: '^7.0.0' }
  ]
};
```

### 3.2 React 组件实现 (FileViewer.tsx)

```typescript
import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Spin, Alert, Space, Tooltip, message } from 'antd';
import {
  ZoomInOutlined,
  ZoomOutOutlined,
  RotateLeftOutlined,
  RotateRightOutlined,
  DownloadOutlined,
  PrinterOutlined,
  FullscreenOutlined,
  FullscreenExitOutlined,
  FileUnknownOutlined
} from '@ant-design/icons';
import { useLowCodeComponent } from '@lowcode/sdk';

import { ImageViewer } from './viewers/ImageViewer';
import { PdfViewer } from './viewers/PdfViewer';
import { DocumentViewer } from './viewers/DocumentViewer';
import { VideoPlayer } from './viewers/VideoPlayer';
import { AudioPlayer } from './viewers/AudioPlayer';
import { Watermark } from './components/Watermark';
import { Toolbar } from './components/Toolbar';
import { FileInfo, ViewerType, FileViewerProps } from './types';

import './FileViewer.less';

const FileViewer: React.FC<FileViewerProps> = (props) => {
  const {
    fileId,
    fileUrl,
    viewerType: propViewerType = 'auto',
    width = '100%',
    height = '500px',
    theme = 'light',
    showToolbar = true,
    toolbarItems = ['zoom', 'rotate', 'download', 'print', 'fullscreen'],
    allowDownload = false,
    allowPrint = false,
    allowFullscreen = true,
    watermark,
    lazyLoad = true,
    cacheEnabled = true,
    errorFallback = '文件加载失败，请重试'
  } = props;

  // 使用 LowCode SDK
  const {
    context,
    invokeAction,
    emit,
    loading: actionLoading,
    error: actionError
  } = useLowCodeComponent();

  // 组件状态
  const [fileInfo, setFileInfo] = useState<FileInfo | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string>('');
  const [viewerType, setViewerType] = useState<ViewerType>('auto');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [zoom, setZoom] = useState(1);
  const [rotation, setRotation] = useState(0);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [currentPage, setCurrentPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);

  const containerRef = useRef<HTMLDivElement>(null);

  // 加载文件
  useEffect(() => {
    if (!fileId && !fileUrl) {
      setError(new Error('请提供 fileId 或 fileUrl'));
      return;
    }

    loadFile();
  }, [fileId, fileUrl]);

  // 监听 Action 错误
  useEffect(() => {
    if (actionError) {
      setError(actionError);
      emit('onError', actionError, actionError.code);
    }
  }, [actionError]);

  // 加载文件信息
  const loadFile = async () => {
    setLoading(true);
    setError(null);

    try {
      let info: FileInfo;
      let url: string;
      let type: ViewerType;

      if (fileId) {
        // 通过 Action 加载文件信息
        const result = await invokeAction('loadFile', { fileId });
        info = result.data;

        // 获取预览 URL
        const previewResult = await invokeAction('getPreviewUrl', {
          fileId,
          watermark: watermark?.enabled ? {
            enabled: true,
            text: renderWatermarkText(watermark.text),
            opacity: watermark.opacity,
            fontSize: watermark.fontSize,
            color: watermark.color
          } : { enabled: false }
        });

        url = previewResult.data.url;
        type = propViewerType === 'auto'
          ? previewResult.data.viewerType.toLowerCase()
          : propViewerType;
      } else {
        // 直接使用 URL
        url = fileUrl!;
        type = propViewerType === 'auto'
          ? detectFileType(fileUrl!)
          : propViewerType;
        info = { fileId: '', originalName: '', fileType: type };
      }

      setFileInfo(info);
      setPreviewUrl(url);
      setViewerType(type);

      // 触发加载完成事件
      emit('onLoad', info);
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      setError(error);
      emit('onError', error, 'LOAD_ERROR');
    } finally {
      setLoading(false);
    }
  };

  // 渲染水印文字（支持变量）
  const renderWatermarkText = (template?: string): string => {
    if (!template) {
      return `${context.user.name} ${context.user.id}`;
    }

    return template
      .replace('{username}', context.user.name)
      .replace('{userId}', context.user.id)
      .replace('{datetime}', new Date().toLocaleString());
  };

  // 自动检测文件类型
  const detectFileType = (url: string): ViewerType => {
    const ext = url.split('.').pop()?.toLowerCase() || '';

    const imageExts = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg'];
    const pdfExts = ['pdf'];
    const docExts = ['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'];
    const videoExts = ['mp4', 'webm', 'ogg', 'mov', 'avi'];
    const audioExts = ['mp3', 'wav', 'ogg', 'aac', 'flac'];

    if (imageExts.includes(ext)) return 'image';
    if (pdfExts.includes(ext)) return 'pdf';
    if (docExts.includes(ext)) return 'document';
    if (videoExts.includes(ext)) return 'video';
    if (audioExts.includes(ext)) return 'audio';

    return 'generic';
  };

  // 处理缩放
  const handleZoomIn = () => {
    const newZoom = Math.min(zoom + 0.25, 3);
    setZoom(newZoom);
    emit('onZoomChange', newZoom);
  };

  const handleZoomOut = () => {
    const newZoom = Math.max(zoom - 0.25, 0.25);
    setZoom(newZoom);
    emit('onZoomChange', newZoom);
  };

  // 处理旋转
  const handleRotateLeft = () => {
    const newRotation = rotation - 90;
    setRotation(newRotation);
    emit('onRotate', newRotation);
  };

  const handleRotateRight = () => {
    const newRotation = rotation + 90;
    setRotation(newRotation);
    emit('onRotate', newRotation);
  };

  // 处理下载
  const handleDownload = async () => {
    if (!fileInfo) return;

    try {
      emit('onDownload', fileInfo);

      if (fileId) {
        // 通过 Action 下载
        await invokeAction('download', { fileId });
      } else if (fileUrl) {
        // 直接下载
        const link = document.createElement('a');
        link.href = fileUrl;
        link.download = fileInfo.originalName || 'download';
        link.click();
      }
    } catch (err) {
      message.error('下载失败');
    }
  };

  // 处理打印
  const handlePrint = () => {
    if (!previewUrl) return;

    const printWindow = window.open(previewUrl, '_blank');
    if (printWindow) {
      printWindow.onload = () => {
        printWindow.print();
      };
    }
  };

  // 处理全屏
  const toggleFullscreen = () => {
    if (!containerRef.current) return;

    if (!isFullscreen) {
      containerRef.current.requestFullscreen?.();
    } else {
      document.exitFullscreen?.();
    }
    setIsFullscreen(!isFullscreen);
  };

  // 处理页码变更
  const handlePageChange = (page: number) => {
    setCurrentPage(page);
    emit('onPageChange', page, totalPages);
  };

  // 渲染阅览器内容
  const renderViewer = () => {
    if (!previewUrl) return null;

    const commonProps = {
      url: previewUrl,
      zoom,
      rotation,
      onPageChange: handlePageChange,
      onLoad: () => setLoading(false),
      onError: (err: Error) => {
        setError(err);
        emit('onError', err, 'VIEWER_ERROR');
      }
    };

    switch (viewerType) {
      case 'image':
        return <ImageViewer {...commonProps} />;
      case 'pdf':
        return (
          <PdfViewer
            {...commonProps}
            currentPage={currentPage}
            onTotalPagesChange={setTotalPages}
          />
        );
      case 'document':
        return <DocumentViewer {...commonProps} />;
      case 'video':
        return <VideoPlayer url={previewUrl} />;
      case 'audio':
        return <AudioPlayer url={previewUrl} />;
      default:
        return (
          <div className="file-viewer-fallback">
            <FileUnknownOutlined style={{ fontSize: 64 }} />
            <p>暂不支持预览此类型的文件</p>
            {allowDownload && (
              <button onClick={handleDownload}>下载文件</button>
            )}
          </div>
        );
    }
  };

  // 渲染工具栏
  const renderToolbar = () => {
    if (!showToolbar) return null;

    const items = [];

    if (toolbarItems.includes('zoom')) {
      items.push(
        <Tooltip title="放大" key="zoomIn">
          <button onClick={handleZoomIn}><ZoomInOutlined /></button>
        </Tooltip>,
        <span key="zoomValue" className="zoom-value">{Math.round(zoom * 100)}%</span>,
        <Tooltip title="缩小" key="zoomOut">
          <button onClick={handleZoomOut}><ZoomOutOutlined /></button>
        </Tooltip>
      );
    }

    if (toolbarItems.includes('rotate')) {
      items.push(
        <Tooltip title="向左旋转" key="rotateLeft">
          <button onClick={handleRotateLeft}><RotateLeftOutlined /></button>
        </Tooltip>,
        <Tooltip title="向右旋转" key="rotateRight">
          <button onClick={handleRotateRight}><RotateRightOutlined /></button>
        </Tooltip>
      );
    }

    if (toolbarItems.includes('download') && allowDownload) {
      items.push(
        <Tooltip title="下载" key="download">
          <button onClick={handleDownload}><DownloadOutlined /></button>
        </Tooltip>
      );
    }

    if (toolbarItems.includes('print') && allowPrint) {
      items.push(
        <Tooltip title="打印" key="print">
          <button onClick={handlePrint}><PrinterOutlined /></button>
        </Tooltip>
      );
    }

    if (toolbarItems.includes('fullscreen') && allowFullscreen) {
      items.push(
        <Tooltip title={isFullscreen ? '退出全屏' : '全屏'} key="fullscreen">
          <button onClick={toggleFullscreen}>
            {isFullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
          </button>
        </Tooltip>
      );
    }

    if (viewerType === 'pdf') {
      items.push(
        <span key="pageInfo" className="page-info">
          {currentPage} / {totalPages}
        </span>
      );
    }

    return (
      <Toolbar className="file-viewer-toolbar">
        <Space>{items}</Space>
      </Toolbar>
    );
  };

  return (
    <div
      ref={containerRef}
      className={`file-viewer file-viewer-${theme} ${isFullscreen ? 'fullscreen' : ''}`}
      style={{ width, height }}
    >
      {renderToolbar()}

      <div className="file-viewer-content">
        {loading && (
          <div className="file-viewer-loading">
            <Spin size="large" tip="加载中..." />
          </div>
        )}

        {error && (
          <Alert
            className="file-viewer-error"
            message="加载失败"
            description={error.message || errorFallback}
            type="error"
            showIcon
            action={
              <button onClick={loadFile}>重试</button>
            }
          />
        )}

        {!loading && !error && (
          <Watermark
            enabled={watermark?.enabled}
            text={renderWatermarkText(watermark?.text)}
            opacity={watermark?.opacity}
            fontSize={watermark?.fontSize}
            color={watermark?.color}
          >
            {renderViewer()}
          </Watermark>
        )}
      </div>
    </div>
  );
};

export default FileViewer;
```

### 3.3 PDF 查看器 (PdfViewer.tsx)

```typescript
import React, { useState, useEffect, useRef } from 'react';
import { Document, Page, pdfjs } from 'react-pdf';
import 'react-pdf/dist/esm/Page/AnnotationLayer.css';
import 'react-pdf/dist/esm/Page/TextLayer.css';

// 设置 PDF.js worker
pdfjs.GlobalWorkerOptions.workerSrc = `//cdnjs.cloudflare.com/ajax/libs/pdf.js/${pdfjs.version}/pdf.worker.min.js`;

interface PdfViewerProps {
  url: string;
  zoom: number;
  rotation: number;
  currentPage: number;
  onPageChange: (page: number) => void;
  onTotalPagesChange: (pages: number) => void;
  onLoad: () => void;
  onError: (error: Error) => void;
}

export const PdfViewer: React.FC<PdfViewerProps> = ({
  url,
  zoom,
  rotation,
  currentPage,
  onPageChange,
  onTotalPagesChange,
  onLoad,
  onError
}) => {
  const [numPages, setNumPages] = useState(0);
  const [pageWidth, setPageWidth] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (containerRef.current) {
      setPageWidth(containerRef.current.clientWidth * zoom);
    }
  }, [zoom]);

  const onDocumentLoadSuccess = ({ numPages }: { numPages: number }) => {
    setNumPages(numPages);
    onTotalPagesChange(numPages);
    onLoad();
  };

  const onDocumentLoadError = (error: Error) => {
    onError(error);
  };

  return (
    <div ref={containerRef} className="pdf-viewer">
      <Document
        file={url}
        onLoadSuccess={onDocumentLoadSuccess}
        onLoadError={onDocumentLoadError}
        loading={null}
      >
        <Page
          pageNumber={currentPage}
          width={pageWidth}
          rotate={rotation}
          renderTextLayer={true}
          renderAnnotationLayer={true}
          loading={null}
        />
      </Document>

      {numPages > 1 && (
        <div className="pdf-pagination">
          <button
            disabled={currentPage <= 1}
            onClick={() => onPageChange(currentPage - 1)}
          >
            上一页
          </button>
          <span>
            {currentPage} / {numPages}
          </span>
          <button
            disabled={currentPage >= numPages}
            onClick={() => onPageChange(currentPage + 1)}
          >
            下一页
          </button>
        </div>
      )}
    </div>
  );
};
```

### 3.4 水印组件 (Watermark.tsx)

```typescript
import React, { useMemo } from 'react';

interface WatermarkProps {
  enabled?: boolean;
  text?: string;
  opacity?: number;
  fontSize?: number;
  color?: string;
  rotation?: number;
  children: React.ReactNode;
}

export const Watermark: React.FC<WatermarkProps> = ({
  enabled = false,
  text = '',
  opacity = 0.15,
  fontSize = 14,
  color = '#FF0000',
  rotation = 45,
  children
}) => {
  const watermarkStyle = useMemo(() => {
    if (!enabled || !text) return {};

    // 创建水印画布
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d')!;

    // 计算水印尺寸
    const textWidth = text.length * fontSize;
    const size = Math.max(200, textWidth);
    canvas.width = size;
    canvas.height = size;

    // 绘制水印
    ctx.save();
    ctx.translate(size / 2, size / 2);
    ctx.rotate((rotation * Math.PI) / 180);
    ctx.font = `${fontSize}px Arial`;
    ctx.fillStyle = color;
    ctx.globalAlpha = opacity;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(text, 0, 0);
    ctx.restore();

    // 生成背景图
    const dataUrl = canvas.toDataURL();

    return {
      backgroundImage: `url(${dataUrl})`,
      backgroundRepeat: 'repeat'
    };
  }, [enabled, text, opacity, fontSize, color, rotation]);

  return (
    <div className="watermark-container" style={watermarkStyle}>
      {children}
    </div>
  );
};
```

### 3.5 样式文件 (FileViewer.less)

```less
.file-viewer {
  display: flex;
  flex-direction: column;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  overflow: hidden;

  &-dark {
    background: #1f1f1f;
    border-color: #434343;
    color: #fff;
  }

  &.fullscreen {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 9999;
    border-radius: 0;
  }

  &-toolbar {
    display: flex;
    align-items: center;
    padding: 8px 12px;
    background: #fafafa;
    border-bottom: 1px solid #d9d9d9;

    .file-viewer-dark & {
      background: #2c2c2c;
      border-color: #434343;
    }

    button {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 32px;
      height: 32px;
      border: 1px solid transparent;
      background: transparent;
      cursor: pointer;
      border-radius: 4px;
      transition: all 0.2s;

      &:hover {
        background: #f0f0f0;
        border-color: #d9d9d9;
      }

      .file-viewer-dark &:hover {
        background: #3c3c3c;
        border-color: #434343;
      }

      &:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
    }

    .zoom-value {
      min-width: 60px;
      text-align: center;
      font-size: 14px;
    }

    .page-info {
      margin-left: auto;
      font-size: 14px;
      color: #666;

      .file-viewer-dark & {
        color: #999;
      }
    }
  }

  &-content {
    flex: 1;
    position: relative;
    overflow: auto;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &-loading {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
  }

  &-error {
    margin: 20px;
    max-width: 400px;
  }

  &-fallback {
    text-align: center;
    padding: 40px;
    color: #999;

    p {
      margin: 16px 0;
    }

    button {
      padding: 8px 16px;
      border: 1px solid #1890ff;
      background: #1890ff;
      color: #fff;
      border-radius: 4px;
      cursor: pointer;

      &:hover {
        background: #40a9ff;
        border-color: #40a9ff;
      }
    }
  }
}

// PDF 查看器样式
.pdf-viewer {
  width: 100%;
  height: 100%;
  overflow: auto;
  padding: 20px;

  .react-pdf__Document {
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .react-pdf__Page {
    margin-bottom: 20px;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  }
}

.pdf-pagination {
  position: fixed;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  background: rgba(255, 255, 255, 0.9);
  border-radius: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);

  button {
    padding: 4px 12px;
    border: 1px solid #d9d9d9;
    background: #fff;
    border-radius: 4px;
    cursor: pointer;

    &:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
  }
}

// 水印样式
.watermark-container {
  width: 100%;
  height: 100%;
}
```

### 3.6 组件入口 (index.ts)

```typescript
import FileViewer from './FileViewer';
import { FileViewerDefinition } from './FileViewer.definition';

// 导出组件定义
export { FileViewerDefinition };

// 导出组件
export default FileViewer;

// 注册到全局
if (typeof window !== 'undefined') {
  (window as any).LowCodeComponents = (window as any).LowCodeComponents || {};
  (window as any).LowCodeComponents[FileViewerDefinition.id] = {
    component: FileViewer,
    definition: FileViewerDefinition
  };
}
```

---

## 4. 集成配置

### 4.1 后端注册

```java
@Component
public class FileViewerActionRegistrar {

    @Autowired
    private ActionRegistryService registryService;

    @PostConstruct
    public void register() {
        // 注册文件相关 Actions
        registryService.registerFromClass(FileResource.class);

        // 或者手动注册
        registryService.register(ActionMetadata.builder()
            .apiVersion("action.example.io/v1")
            .kind("Action")
            .metadata(ActionMetadata.Metadata.builder()
                .name("get")
                .namespace("storage.file")
                .version("1.0.0")
                .title("获取文件信息")
                .build())
            .spec(ActionMetadata.Spec.builder()
                .type("SYNC")
                .protocol("HTTP")
                .endpoint(Endpoint.builder()
                    .service("storage-service")
                    .path("/api/storage/file/get")
                    .method("POST")
                    .build())
                .build())
            .build());
    }
}
```

### 4.2 前端注册

```typescript
// app.tsx
import { ComponentRegistry } from '@lowcode/sdk';
import { FileViewerDefinition } from './components/FileViewer';

const registry = new ComponentRegistry();

// 注册文件阅览组件
registry.register(FileViewerDefinition);

// 或者批量注册
registry.registerBatch([
  FileViewerDefinition,
  // 其他组件...
]);

// 渲染页面
const App = () => {
  return (
    <LowCodeRuntime registry={registry}>
      <PageRenderer pageId="document-page" />
    </LowCodeRuntime>
  );
};
```

### 4.3 页面配置示例

```json
{
  "id": "document-page",
  "title": "文档预览页面",
  "components": [
    {
      "id": "file-viewer-1",
      "componentId": "data.file-viewer",
      "props": {
        "fileId": "${query.fileId}",
        "viewerType": "auto",
        "width": "100%",
        "height": "600px",
        "showToolbar": true,
        "allowDownload": false,
        "allowPrint": false,
        "watermark": {
          "enabled": true,
          "text": "{username} {datetime}",
          "opacity": 0.15,
          "fontSize": 14,
          "color": "#FF0000"
        }
      },
      "events": [
        {
          "name": "onDownload",
          "actions": [
            {
              "type": "audit",
              "params": {
                "action": "FILE_DOWNLOAD",
                "fileId": "${props.fileId}"
              }
            }
          ]
        }
      ]
    }
  ]
}
```

---

## 5. 使用示例

### 5.1 基础用法

```tsx
// 使用 fileId 加载
<FileViewer
  fileId="file_abc123xyz"
  showToolbar={true}
  allowDownload={false}
/>

// 使用 URL 加载
<FileViewer
  fileUrl="https://example.com/docs/report.pdf"
  viewerType="pdf"
  height="800px"
/>
```

### 5.2 带水印的机密文档

```tsx
<FileViewer
  fileId="confidential_doc_001"
  watermark={{
    enabled: true,
    text: "机密 - {username} - {datetime}",
    opacity: 0.2,
    fontSize: 16,
    color: "#FF0000"
  }}
  allowDownload={false}
  allowPrint={false}
  onDownload={(fileInfo) => {
    message.warning('机密文档禁止下载');
  }}
/>
```

### 5.3 配合表单使用

```tsx
const DocumentApproval = () => {
  const [selectedFile, setSelectedFile] = useState(null);

  return (
    <div className="document-approval">
      <div className="file-list">
        <Table
          dataSource={files}
          onRow={(record) => ({
            onClick: () => setSelectedFile(record)
          })}
        />
      </div>
      <div className="file-preview">
        {selectedFile && (
          <FileViewer
            fileId={selectedFile.fileId}
            watermark={{
              enabled: true,
              text: `审批人: ${currentUser.name}`
            }}
            onPageChange={(page, total) => {
              console.log(`阅读进度: ${page}/${total}`);
            }}
          />
        )}
      </div>
    </div>
  );
};
```

---

## 6. 总结

这个文件阅览组件示例展示了：

1. **完整的后端 Action 实现**：包含文件信息获取、预览URL生成、下载、内容提取等功能
2. **丰富的前端组件**：支持多种文件格式、工具栏、水印、权限控制
3. **前后端联动**：通过 Action 绑定配置自动关联前后端
4. **配置化设计**：通过 JSON Schema 定义支持低代码配置
5. **安全性**：水印、权限控制、审计日志

该组件可以作为低代码平台的内置组件或自定义插件使用。
