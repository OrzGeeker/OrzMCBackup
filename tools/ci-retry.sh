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
  output=$("$@" 2>&1)
  rc=$?
  echo "::endgroup::"

  if [ "${rc}" -eq 0 ]; then
    echo "Command succeeded on attempt ${attempt}."
    exit 0
  fi

  if [ "${attempt}" -ge "${max_attempts}" ] || ! retriable "${output}"; then
    echo "Command failed on attempt ${attempt} (exit ${rc}) — not a retriable network error." >&2
    echo "${output}" >&2
    exit "${rc}"
  fi

  echo "Transient dependency-download failure on attempt ${attempt}; retrying in ${backoff}s..."
  echo "$(printf '%s\n' "${output}" | tail -n 25)"
  sleep "${backoff}"
  attempt=$((attempt + 1))
done
