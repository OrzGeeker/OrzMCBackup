#!/usr/bin/env bash
# CI 依赖下载失败自动重试。
#
# 背景：test-matrix 的多个矩阵 job 在冷缓存时并发拉取依赖，Maven Central
# 会返回 HTTP 429（Too Many Requests）导致偶发失败。这类错误是瞬时的，
# 重试即可恢复；而真实失败（测试断言、编译、风格检查等）必须立即上报。
#
# 用法：bash tools/ci-retry.sh <command> [args...]
# 环境变量：RETRY_MAX_ATTEMPTS（默认 3）、RETRY_BACKOFF（默认 15 秒）
set -u

max_attempts="${RETRY_MAX_ATTEMPTS:-3}"
backoff="${RETRY_BACKOFF:-15}"

# 依赖下载类瞬时错误特征（命中才重试）
retriable() {
  local re
  re='could not (resolve|get|download|list)|too many requests|429|connection (refused|reset|timed out)|remote host closed connection|socket (read|write) timed out|unable to resolve host|read timed out|unknown host'
  grep -qiE "$re" <<< "$1"
}

attempt=1
while :; do
  echo "::group::Attempt ${attempt}/${max_attempts}: $*"
  # C16: 流式打印 —— tee 到临时文件而不是缓存全量输出到变量，
  # 这样长任务（test/coverage）的日志边跑边出，同时保留完整日志供重试判定。
  log="$(mktemp)"
  "$@" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  echo "::endgroup::"

  if [ "${rc}" -eq 0 ]; then
    rm -f "$log"
    echo "Command succeeded on attempt ${attempt}."
    exit 0
  fi

  if [ "${attempt}" -ge "${max_attempts}" ] || ! retriable "$(cat "$log")"; then
    echo "Command failed on attempt ${attempt} (exit ${rc}) — not a retriable network error." >&2
    cat "$log" >&2
    rm -f "$log"
    exit "${rc}"
  fi

  echo "Transient dependency-download failure on attempt ${attempt}; retrying in ${backoff}s..."
  tail -n 25 "$log"
  rm -f "$log"
  sleep "${backoff}"
  attempt=$((attempt + 1))
done
