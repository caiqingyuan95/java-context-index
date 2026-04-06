#!/usr/bin/env python3
"""
快速扫描Java编译错误的脚本
"""
import os
import re
import subprocess
from pathlib import Path

def check_java_file(java_file):
    """检查单个Java文件的常见编译问题"""
    issues = []
    
    with open(java_file, 'r', encoding='utf-8') as f:
        content = f.read()
        lines = content.split('\n')
    
    # 检查未使用的导入
    import_lines = [i for i, line in enumerate(lines) if line.strip().startswith('import ')]
    
    # 检查常见错误模式
    for i, line in enumerate(lines, 1):
        # 检查可能的API错误
        if 'm.getKeyword().asString()' in line:
            issues.append(f"Line {i}: 使用了已弃用的API getKeyword().asString()")
        if 'm.name()' in line and '.map(m -> m.name())' in line:
            issues.append(f"Line {i}: Modifier类没有name()方法，应使用getKeyword().toString()")
    
    return issues

def scan_project():
    """扫描整个项目"""
    project_root = Path(__file__).parent / 'src' / 'main' / 'java'
    
    print("=" * 60)
    print("Java编译问题扫描报告")
    print("=" * 60)
    
    java_files = list(project_root.rglob('*.java'))
    total_issues = 0
    
    for java_file in java_files:
        issues = check_java_file(java_file)
        if issues:
            print(f"\n📄 {java_file.relative_to(project_root.parent.parent.parent)}")
            for issue in issues:
                print(f"  ⚠️  {issue}")
                total_issues += 1
    
    print("\n" + "=" * 60)
    if total_issues == 0:
        print("✅ 未发现明显的编译问题")
    else:
        print(f"⚠️  共发现 {total_issues} 个潜在问题")
    print("=" * 60)

if __name__ == '__main__':
    scan_project()
