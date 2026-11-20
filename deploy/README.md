# 배포 (Docker + AWS EC2)

`main`으로 merge되면 GitHub Actions의 CD 워크플로우가 서버를 EC2에 새로 띄웁니다.

```
PR merge → main push
  └─ CD (.github/workflows/cd.yml)
       1. Build & Test        ./gradlew build
       2. Docker image → GHCR  ghcr.io/solseok/hansung_arcade_be:<커밋 SHA>, :latest
       3. Deploy to EC2        SSH로 deploy/ec2-deploy.sh 실행
                               → 이미지 받기 → 기존 컨테이너 종료(SIGTERM, 15초) → 새 컨테이너 시작
                               → 시작 실패·죽음·30초 안에 "구동 완료" 없음이면 이전 이미지로 되돌리고 실패
```

PR과 `develop` push에서는 CI(`.github/workflows/ci.yml`)가 빌드와 테스트만 합니다.
Actions 화면에서 CD를 직접 실행(`Run workflow`)할 수도 있지만, 이미지와 배포 단계는 `main`에서 실행할 때만 돕니다.

## 1. EC2 준비 (한 번)

1. 인스턴스를 만듭니다. Amazon Linux 2023이나 Ubuntu, t3.micro 이상을 쓰면 됩니다.
   - Graviton(ARM)을 쓰면 "2. GitHub 설정"의 `IMAGE_PLATFORM`을 꼭 설정하세요.
2. 보안 그룹 인바운드 규칙을 엽니다.
   - `9000/tcp`: 게임 클라이언트가 접속합니다. 수업이나 팀 범위라면 필요한 IP 대역만 엽니다.
   - `22/tcp`: GitHub Actions가 SSH로 들어옵니다. Actions 러너 IP는 고정되어 있지 않습니다.
3. Docker를 설치하고, 배포 계정을 `docker` 그룹에 넣습니다.

   ```bash
   # Amazon Linux 2023
   sudo dnf install -y docker
   sudo systemctl enable --now docker
   sudo usermod -aG docker ec2-user   # 다시 로그인해야 적용됩니다
   ```

   ```bash
   # Ubuntu
   sudo apt-get update && sudo apt-get install -y docker.io
   sudo systemctl enable --now docker
   sudo usermod -aG docker ubuntu
   ```

4. 배포 전용 SSH 키를 만들고, 공개 키를 EC2에 등록합니다. 아래는 로컬에서 하는 일입니다.

   ```bash
   ssh-keygen -t ed25519 -f hansung-deploy -N "" -C "github-actions-deploy"
   # EC2는 비밀번호 로그인이 막혀 있으므로, 인스턴스를 만들 때 받은 .pem 키로 접속해 공개 키를 등록한다
   ssh-copy-id -i hansung-deploy.pub -o "IdentityFile=<인스턴스 생성 때 받은 .pem>" ec2-user@<EC2 주소>
   ```

   EC2의 `~/.ssh/authorized_keys`에서 이 키 줄 앞에 `restrict `를 붙이면 포트 포워딩, 에이전트 전달, pty를 막을 수 있습니다. 명령 실행은 그대로 됩니다.

5. 아래 명령의 결과를 `EC2_KNOWN_HOSTS` 시크릿에 넣습니다. **필수**입니다. 없으면 배포가 실패합니다. 호스트 키를 고정해 두지 않으면 중간자가 EC2인 척할 수 있습니다.

   ```bash
   ssh-keyscan -H <EC2 주소>
   ```

## 2. GitHub 설정

**먼저 `Settings → Environments`에서 `production` 환경을 만들고, `Deployment branches and tags`를 `main`으로 제한합니다.** 시크릿은 저장소 시크릿이 아니라 이 **환경 시크릿**으로 넣습니다. 저장소 시크릿은 쓰기 권한이 있는 사람이 아무 브랜치의 워크플로우에서나 꺼내 볼 수 있지만, 환경 시크릿은 `main`에서 도는 배포 job만 읽을 수 있습니다. 배포 전에 승인을 받으려면 `Required reviewers`도 겁니다(선택).

| 종류 | 이름 | 값 | 필수 |
|---|---|---|---|
| 환경 시크릿 | `EC2_HOST` | EC2 공인 IP나 도메인(탄력적 IP를 권장) | 예 |
| 환경 시크릿 | `EC2_SSH_KEY` | 위에서 만든 `hansung-deploy` 개인 키 전체 | 예 |
| 환경 시크릿 | `EC2_KNOWN_HOSTS` | `ssh-keyscan -H <EC2 주소>` 결과 | 예 |
| 환경 시크릿 | `EC2_USER` | SSH 계정. 없으면 `ec2-user` | 아니요 |
| 저장소 변수 | `SERVER_PORT` | EC2에서 열 포트. 없으면 `9000` | 아니요 |
| 저장소 변수 | `SERVER_MEMORY` | 컨테이너 메모리 한도. 없으면 `512m`. 힙은 이 값의 75% | 아니요 |
| 저장소 변수 | `IMAGE_PLATFORM` | Graviton이면 `linux/arm64`. 없으면 `linux/amd64` | 아니요 |

GHCR 로그인에는 워크플로우가 받는 `GITHUB_TOKEN`을 쓰므로, 따로 넣을 토큰은 없습니다. EC2는 배포하는 동안에만 이 토큰으로 로그인하고, 배포가 끝나면 바로 로그아웃합니다.

## 3. 직접 다루기

```bash
# EC2에서 상태와 로그 보기
docker ps --filter name=hansung-arcade-server
docker logs -f hansung-arcade-server

# 로컬에서 이미지 빌드·실행
docker build -t hansung-arcade-server .
docker run --rm -p 9000:9000 hansung-arcade-server
```

다시 배포하려면 Actions에서 CD를 `Run workflow`로 실행합니다. 특정 커밋으로 되돌리려면 EC2에서 아래처럼 실행합니다.

```bash
docker pull ghcr.io/solseok/hansung_arcade_be:<SHA>   # 패키지가 비공개면 먼저 docker login ghcr.io
docker rm -f hansung-arcade-server
# 아래 옵션은 deploy/ec2-deploy.sh의 start()와 같다. SERVER_PORT, SERVER_MEMORY를 바꿨으면 맞춰 쓴다
docker run -d --name hansung-arcade-server --restart unless-stopped \
  -p 9000:9000 --memory 512m --security-opt no-new-privileges --cap-drop ALL \
  --log-opt max-size=20m --log-opt max-file=5 \
  ghcr.io/solseok/hansung_arcade_be:<SHA>
```

## 알아 둘 점

- 배포 계정은 `docker` 그룹이라 사실상 root 권한을 가집니다. 배포 키가 새면 EC2 전체가 넘어가므로, 키는 환경 시크릿에만 두고 이 용도로만 씁니다. 22번 포트를 닫고 싶으면 SSH 대신 AWS SSM(Run Command + GitHub OIDC)으로 바꾸는 방법이 있습니다.

- 배포하는 몇 초 동안 서버가 내려갑니다. 그동안 접속해 있던 사람은 끊기고, 접속 상태는 메모리에만 있으므로 사라집니다. 게임 중이 아닐 때 merge하세요.
- 로그는 Docker가 20 MB × 5개까지 보관합니다.
- 로그에는 ANSI 색이 들어갑니다. 색 없이 보려면 `ec2-deploy.sh`의 `docker run`에 `-e NO_COLOR=1`을 더합니다.
