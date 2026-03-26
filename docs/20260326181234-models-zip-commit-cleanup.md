# 主题：models-zip-commit-cleanup

## 记录

### 2026-03-26 18:12:34

## 本次目标
- 将误加入最新提交的 `apps/server/models.zip` 从 commit 中移除，避免大体积模型压缩包进入版本库。

## 关键改动
- 更新 `.gitignore`，新增 `/apps/server/models.zip` 忽略规则，防止后续再次误提交。
- 执行 `git rm --cached -- apps/server/models.zip`，将该文件从 Git 跟踪中移除。
- 使用 `git commit --amend --no-edit` 改写最新提交，将 `models.zip` 从最近一次提交中剔除。
- 最新提交由 `4d93675` 改写为 `61ef2e2`，`git show` 确认提交内容中已不再包含 `apps/server/models.zip`。

## 验证结果
- `git ls-files apps/server/models.zip` 无输出，确认文件已不再被 Git 跟踪。
- `git check-ignore -v apps/server/models.zip` 命中 `.gitignore` 中新增规则。
- 后端 clean 启动成功：`mvn -f apps/server/pom.xml clean spring-boot:run '-Dspring-boot.run.arguments=--server.port=18141' '-Dfile.encoding=UTF-8'`，`GET /api/health` 返回 `{"code":0,"message":"success","data":"ok"}`。
- 前端 clean build 成功：`npm run -w @sqlcopilot/desktop build -- --emptyOutDir`。
- 前端 preview 成功：`npm run -w @sqlcopilot/desktop preview -- --host 127.0.0.1 --port 6080 --strictPort`，`curl -I http://127.0.0.1:6080/` 返回 `HTTP/1.1 200 OK`。

## 遗留项
- 如果旧提交 `4d93675` 已经推送到远端，后续推送需要使用改写历史的方式同步远端分支。
