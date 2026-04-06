# 快速编译并捕获错误
cd g:\code\cqy\java-context-index

Write-Host "开始编译项目..." -ForegroundColor Green

# 编译并捕获输出
$compileOutput = mvn compile -DskipTests 2>&1

# 显示所有错误
$errors = $compileOutput | Select-String -Pattern "error:|ERROR|找不到符号|无法"

if ($errors.Count -gt 0) {
    Write-Host "`n发现 $($errors.Count) 个编译错误:" -ForegroundColor Red
    Write-Host "=" * 60
    $errors | ForEach-Object {
        Write-Host $_ -ForegroundColor Yellow
    }
    Write-Host "=" * 60
} else {
    Write-Host "`n编译成功! ✅" -ForegroundColor Green
}

# 显示警告
$warnings = $compileOutput | Select-String -Pattern "warning:|WARNING"

if ($warnings.Count -gt 0) {
    Write-Host "`n发现 $($warnings.Count) 个警告:" -ForegroundColor Yellow
}
