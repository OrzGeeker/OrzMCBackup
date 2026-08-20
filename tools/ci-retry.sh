#!/usr/bin/env bash
# CI 依赖下载失败自动重试。
#
# 背景：test-matrix 的多个矩阵 job 在冷缓存时并发拉取依赖，Maven Central
# 会返回 HTTP 429（Too Many Requests）导致偶发失败。这类错误是瞬时的，
# 重试即可恢复；而真实失败（测试断言、编译、风格检查等）必须立即上报。
#
# 用法：bash tools/ci-retry.sh <command> [args...]
# 环境变量：RETRY_MAX_ATTEMPTS（默认 5）、RETRY_BACKOFF（默认 10 秒，指数退避基数）
set -u

max_attempts="${RETRY_MAX_ATTEMPTS:-5}"
base_backoff="${RETRY_BACKOFF:-10}"

# 依赖下载类瞬时错误特征（命中才重试）
# C14: 补全网关层状态码（5xx）与 codeload 特征 —— 冷缓存并发时 GitHub 拉取也可能 502/503
retriable() {
  local re
  re='could not (resolve|get|download|list)|too many requests|429|50[234]|connection (refused|reset|timed out)|remote host closed connection|socket (read|write) timed out|unable to resolve host|read timed out|unknown host|bad gateway|service unavailable|gateway timeout|codeload'
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

  # C15: 指数退避 + jitter —— base * 2^(attempt-1) * (0.5~1.5)。
  # 冷缓存时多个矩阵 job 并发重试，加随机抖动避免同拍集中打爆限流窗口。
  wait_secs=$((base_backoff * (1 << (attempt - 1))))
  wait_secs=$((wait_secs * (50 + RANDOM % 101) / 100))
  echo "Transient dependency-download failure on attempt ${attempt}; retrying in ~${wait_secs}s..."
  tail -n 25 "$log"
  rm -f "$log"
  sleep "${wait_secs}"
  attempt=$((attempt + 1))
done
