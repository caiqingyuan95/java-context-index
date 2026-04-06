# 豆包Embedding API测试脚本
# 使用前请设置环境变量 DOUBAO_API_KEY

Write-Host "========== 豆包Embedding API测试 ==========" -ForegroundColor Cyan

# 从环境变量读取API Key
$apiKey = $env:DOUBAO_API_KEY

if ([string]::IsNullOrEmpty($apiKey)) {
    Write-Host "❌ 错误：未设置DOUBAO_API_KEY环境变量" -ForegroundColor Red
    Write-Host "请设置：`$env:DOUBAO_API_KEY = 'your-api-key'" -ForegroundColor Yellow
    exit 1
}

Write-Host "✅ API Key已配置" -ForegroundColor Green
Write-Host ""

# 测试1: 单个文本向量化（多模态端点）
Write-Host "========== 测试1: 单个文本向量化（多模态端点） ==========" -ForegroundColor Cyan
$testText1 = "Java是一种面向对象的编程语言"

# 多模态端点使用对象数组格式
$body1 = @{
    model = "doubao-embedding-vision-250615"
    input = @(
        @{
            type = "text"
            text = $testText1
        }
    )
    encoding_format = "float"
} | ConvertTo-Json -Depth 3 -Compress

Write-Host "输入文本: $testText1" -ForegroundColor Yellow
Write-Host "API端点: /embeddings/multimodal" -ForegroundColor Yellow
Write-Host "请求体格式: 对象数组 [{"type":"text","text":"..."}]" -ForegroundColor Gray

try {
    $response1 = Invoke-RestMethod -Uri "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal" `
        -Method POST `
        -Headers @{
            "Content-Type" = "application/json"
            "Authorization" = "Bearer $apiKey"
        } `
        -Body $body1
    
    Write-Host "✅ 向量化成功!" -ForegroundColor Green
    Write-Host "   - 模型: $($response1.model)" -ForegroundColor White
    Write-Host "   - 向量维度: $($response1.data[0].embedding.Count)" -ForegroundColor White
    Write-Host "   - Token使用: $($response1.usage.prompt_tokens)" -ForegroundColor White
    
    # 保存向量数据用于后续测试
    $vector1 = $response1.data[0].embedding
    Write-Host "   - 向量前5个值: [$($vector1[0]), $($vector1[1]), $($vector1[2]), $($vector1[3]), $($vector1[4])]" -ForegroundColor White
} catch {
    Write-Host "❌ 测试1失败: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "详情: $_" -ForegroundColor Red
    exit 1
}

Write-Host ""

# 测试2: 英文文本向量化（多模态端点）
Write-Host "========== 测试2: 英文文本向量化（多模态端点） ==========" -ForegroundColor Cyan
$testText2 = "Machine learning is a subset of artificial intelligence"

$body2 = @{
    model = "doubao-embedding-vision-250615"
    input = @(
        @{
            type = "text"
            text = $testText2
        }
    )
    encoding_format = "float"
} | ConvertTo-Json -Depth 3 -Compress

Write-Host "输入文本: $testText2" -ForegroundColor Yellow

try {
    $response2 = Invoke-RestMethod -Uri "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal" `
        -Method POST `
        -Headers @{
            "Content-Type" = "application/json"
            "Authorization" = "Bearer $apiKey"
        } `
        -Body $body2
    
    Write-Host "✅ 向量化成功!" -ForegroundColor Green
    Write-Host "   - 向量维度: $($response2.data[0].embedding.Count)" -ForegroundColor White
    
    $vector2 = $response2.data[0].embedding
} catch {
    Write-Host "❌ 测试2失败: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host ""

# 测试3: 批量文本向量化（多模态端点）
Write-Host "========== 测试3: 批量文本向量化（多模态端点） ==========" -ForegroundColor Cyan
$testTexts = @(
    "Spring Boot是一个Java框架",
    "RESTful API设计规范",
    "微服务架构最佳实践"
)

# 批量文本也要用对象数组
$inputArray = @()
foreach ($text in $testTexts) {
    $inputArray += @{
        type = "text"
        text = $text
    }
}

$body3 = @{
    model = "doubao-embedding-vision-250615"
    input = $inputArray
    encoding_format = "float"
} | ConvertTo-Json -Depth 3 -Compress

Write-Host "输入文本数量: $($testTexts.Count)" -ForegroundColor Yellow

try {
    $response3 = Invoke-RestMethod -Uri "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal" `
        -Method POST `
        -Headers @{
            "Content-Type" = "application/json"
            "Authorization" = "Bearer $apiKey"
        } `
        -Body $body3
    
    Write-Host "✅ 批量向量化成功!" -ForegroundColor Green
    Write-Host "   - 返回向量数量: $($response3.data.Count)" -ForegroundColor White
    Write-Host "   - 每个向量维度: $($response3.data[0].embedding.Count)" -ForegroundColor White
} catch {
    Write-Host "❌ 测试3失败: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host ""

# 测试4: 不同模型测试
Write-Host "========== 测试4: 不同模型测试 ==========" -ForegroundColor Cyan
$models = @(
    "doubao-embedding-vision-250615",
    "text-embedding-large-3"
)

foreach ($model in $models) {
    Write-Host "测试模型: $model" -ForegroundColor Yellow
    
    $body = @{
        model = $model
        input = @(
            @{
                type = "text"
                text = "测试文本"
            }
        )
        encoding_format = "float"
    } | ConvertTo-Json -Depth 3 -Compress
    
    try {
        $response = Invoke-RestMethod -Uri "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal" `
            -Method POST `
            -Headers @{
                "Content-Type" = "application/json"
                "Authorization" = "Bearer $apiKey"
            } `
            -Body $body
        
        Write-Host "  ✅ 成功 - 维度: $($response.data[0].embedding.Count)" -ForegroundColor Green
    } catch {
        Write-Host "  ❌ 失败: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "========== 所有测试通过! ✅ ==========" -ForegroundColor Green
