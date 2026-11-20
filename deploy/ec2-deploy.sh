#!/usr/bin/env bash
# EC2에서 새 서버 이미지로 바꿔 띄운다. CD 워크플로우가 SSH로 이 스크립트를 보내 실행한다.
#
#   사용: ec2-deploy.sh <이미지> <태그> <호스트 포트> <메모리 한도>
#   환경: GHCR_USER, GHCR_TOKEN (ghcr.io에서 이미지를 받을 때만 쓰고 바로 지운다)
#
# 새 컨테이너가 시작조차 못 하거나, 죽거나, 30초 안에 "구동 완료"를 남기지 못하면
# 이전 이미지로 되돌리고 실패로 끝낸다.
set -euo pipefail

IMAGE="$1"
TAG="$2"
HOST_PORT="$3"
MEMORY="$4"
NAME="hansung-arcade-server"
READY_LOG="구동 완료"
READY_TIMEOUT=30
# 되돌릴 수 있게 남겨 둘 이미지 수 (지금 것 포함)
KEEP_IMAGES=3

if ! [[ "${HOST_PORT}" =~ ^[0-9]{1,5}$ ]] || (( HOST_PORT < 1 || HOST_PORT > 65535 )) \
    || ! [[ "${MEMORY}" =~ ^[0-9]+[mg]$ ]]; then
    echo "!!! 포트(${HOST_PORT})나 메모리 한도(${MEMORY}) 형식이 틀렸습니다. 예: 9000, 512m" >&2
    exit 1
fi

echo "==> 이미지 받기: ${IMAGE}:${TAG}"
trap 'docker logout ghcr.io > /dev/null 2>&1 || true' EXIT
printf '%s\n' "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USER}" --password-stdin > /dev/null
unset GHCR_TOKEN
docker pull "${IMAGE}:${TAG}" < /dev/null
docker logout ghcr.io > /dev/null

# 되돌릴 때 쓸 지금 이미지
PREVIOUS="$(docker inspect --format '{{.Config.Image}}' "${NAME}" 2>/dev/null || true)"

start() {
    docker run -d \
        --name "${NAME}" \
        --restart unless-stopped \
        -p "${HOST_PORT}:9000" \
        --memory "${MEMORY}" \
        --security-opt no-new-privileges \
        --cap-drop ALL \
        --log-opt max-size=20m --log-opt max-file=5 \
        "$1" < /dev/null > /dev/null
}

stop() {
    # SIGTERM을 받으면 종료 훅이 연결을 정리한다. 15초 안에 안 끝나면 강제로 끈다
    docker stop -t 15 "${NAME}" > /dev/null 2>&1 || true
    docker rm -f "${NAME}" > /dev/null 2>&1 || true
}

ready() {
    local logs
    for _ in $(seq "${READY_TIMEOUT}"); do
        # 죽었거나, --restart로 다시 뜨기를 되풀이하면 실패
        if [ "$(docker inspect --format '{{.State.Running}}' "${NAME}" 2>/dev/null)" != "true" ] \
            || [ "$(docker inspect --format '{{.RestartCount}}' "${NAME}" 2>/dev/null || echo 0)" -gt 0 ]; then
            return 1
        fi
        logs="$(docker logs "${NAME}" 2>&1 || true)"
        if [[ "${logs}" == *"${READY_LOG}"* ]]; then
            return 0
        fi
        sleep 1
    done
    return 1
}

echo "==> 교체: ${PREVIOUS:-없음} → ${IMAGE}:${TAG}"
stop
if start "${IMAGE}:${TAG}" && ready; then
    echo "==> 구동 확인. 이 서버 이미지는 최근 ${KEEP_IMAGES}개만 남긴다"
    docker images "${IMAGE}" --format '{{.ID}}' | awk '!seen[$0]++' | tail -n +"$((KEEP_IMAGES + 1))" \
        | xargs -r docker rmi > /dev/null 2>&1 || true
    docker image prune -f > /dev/null
    docker ps --filter "name=^${NAME}$" --format '{{.Names}}  {{.Image}}  {{.Status}}  {{.Ports}}'
    exit 0
fi

echo "!!! 새 컨테이너가 뜨지 못했습니다(시작 실패, 죽음, 또는 ${READY_TIMEOUT}초 안에 구동 완료 없음). 마지막 로그:"
docker logs --tail 50 "${NAME}" 2>&1 || true
stop
if [ -z "${PREVIOUS}" ]; then
    echo "!!! 되돌릴 이전 이미지가 없습니다. 서버가 꺼져 있습니다."
    exit 1
fi
echo "==> 이전 이미지로 되돌림: ${PREVIOUS}"
if start "${PREVIOUS}" && ready; then
    echo "==> 이전 이미지로 다시 떴습니다."
else
    echo "!!! 되돌리기도 실패했습니다. 서버가 꺼져 있을 수 있으니 EC2에서 직접 확인하세요."
fi
exit 1
