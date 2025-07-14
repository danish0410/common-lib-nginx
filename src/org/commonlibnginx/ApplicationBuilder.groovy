package org.commonlibnginx

import groovy.json.JsonSlurper

class ApplicationBuilder implements Serializable {
    def steps

    String repoName
    String appType
    String imageName
    String containerName
    String dockerPort = "80"
    String hostPort
    String envStage
    Map parsedMap
    Map repoConfig

    ApplicationBuilder(steps) {
        this.steps = steps
    }

    void cleanWorkspace() {
        steps.echo "🫉 Cleaning workspace..."
        if (steps.isUnix()) {
            steps.sh 'rm -rf *'
        } else {
            steps.bat 'del /F /Q *.* >nul 2>&1'
        }
        steps.echo "✅ Workspace cleaned."
    }

    void initialize() {
        try {
            if (steps.isUnix()) steps.error("❌ This environment is intended for Windows only.")

            repoName = steps.params.REPO_NAME
            if (!repoName?.trim()) steps.error("❌ 'REPO_NAME' must be provided.")

            def configText = steps.libraryResource("common-repo-list.js")
            steps.writeFile(file: "common-repo-list.js", text: configText)

            parsedMap = parseAndNormalizeJson(configText)
            def appTypeKey = findAppType(repoName, parsedMap)
            if (!appTypeKey) steps.error("❌ Repository '${repoName}' not found.")

            appType = appTypeKey.toLowerCase()
            repoConfig = parsedMap[appTypeKey].find { it['repo-name'] == repoName }

            hostPort = findAvailablePort(8081, 8090)
            if (!hostPort) steps.error("❌ No available port found between 8081–8090.")

            imageName = "${repoName.toLowerCase()}-image"
            containerName = "${repoName.toLowerCase()}-container"
            envStage = steps.params.ENV_STAGE ?: 'dev'

            steps.env.APP_TYPE = appType
            steps.env.PROJECT_DIR = repoName
            steps.env.IMAGE_NAME = imageName
            steps.env.CONTAINER_NAME = containerName
            steps.env.DOCKER_PORT = dockerPort
            steps.env.HOST_PORT = hostPort
            steps.env.ENV_STAGE = envStage

            steps.echo "✅ Environment initialized for '${repoName}' on port ${hostPort}"
        } catch (Exception e) {
            steps.error("❌ InitEnv failed: ${e.message ?: e.toString()}")
        }
    }

    void checkout(String branch = 'feature', int timeout = 20) {
        steps.checkout([
            $class: 'GitSCM',
            branches: [[name: "*/${branch}"]],
            extensions: [
                [$class: 'CloneOption', timeout: timeout, shallow: false],
                [$class: 'RelativeTargetDirectory', relativeTargetDir: "target-repo/${repoName}"]
            ],
            userRemoteConfigs: [[
                url: repoConfig["git-url"],
                credentialsId: repoConfig["git_credentials_id"]
            ]]
        ])
    }

    void preRunDebug() {
        steps.echo "🔧 Pre-Run – IMAGE_NAME     = '${steps.env.IMAGE_NAME}'"
        steps.echo "🔧 Pre-Run – CONTAINER_NAME = '${steps.env.CONTAINER_NAME}'"

        if (!steps.env.APP_TYPE) {
            steps.error "❌ Pre-Run check failed: APP_TYPE is null or not initialized!"
        }
    }

    void build(String branch) {
        steps.echo "⚙️ build() invoked"
        buildStaticApp(imageName)
    }

    private void buildStaticApp(String imageName) {
        checkDockerfileExists()
        runCommand("cd target-repo/${repoName} && docker build -t ${imageName}:latest .")
    }

    private void checkDockerfileExists() {
        def dockerfile = steps.findFiles(glob: "**/Dockerfile")
        if (!dockerfile || dockerfile.size() == 0) {
            steps.error("❌ Dockerfile missing.")
        }
    }

    private void runCommand(String command) {
        steps.echo "▶️ Running command: ${command}"
        if (steps.isUnix()) {
            steps.sh(script: command)
        } else {
            steps.bat(script: command)
        }
    }

    void runContainer() {
        if (!containerName || !imageName || !hostPort || !dockerPort)
            steps.error("❌ Missing required parameters.")

        runCommand("docker stop ${containerName} || exit 0")
        runCommand("docker rm ${containerName} || exit 0")

        def runCmd = "docker run -d --name ${containerName} --network spring-net -p ${hostPort}:${dockerPort} ${imageName}:latest"
        runCommand(runCmd)
    }

    void healthCheck() {
        if (!containerName || !hostPort) {
            steps.echo "⚠️ Skipping health check due to missing configuration."
            return
        }

        String url = "http://localhost:${hostPort}/"
        steps.echo "⏳ Starting health check for Nginx app on ${url}"

        try {
            steps.sleep(time: 10, unit: 'SECONDS')
            def success = false

            for (int i = 1; i <= 10; i++) {
                def code = "000"
                try {
                    code = steps.isUnix()
                        ? steps.sh(script: "curl -s -o /dev/null -w \"%{http_code}\" ${url}", returnStdout: true).trim()
                        : extractStatusCode(steps.bat(script: "curl -s -o NUL -w \"%%{http_code}\" ${url}", returnStdout: true))
                } catch (Exception ignored) {}

                steps.echo "🔁 Attempt ${i}: HTTP ${code}"
                if (["200", "403", "302"].contains(code)) {
                    steps.echo "✅ Health check passed with code ${code}"
                    success = true
                    break
                }

                steps.sleep(time: 3, unit: 'SECONDS')
            }

            if (!success) throw new Exception("Service did not become healthy within timeout.")
        } catch (Exception e) {
            steps.echo "❌ Health check failed for container '${containerName}'."
            try {
                runCommand("docker logs ${containerName} || true")
            } catch (ignored) {}
            steps.error("🚨 Health check failed: ${e.message}")
        }
    }

    private String extractStatusCode(String output) {
        def lines = output.readLines().findAll { it.trim() }
        return lines[-1]?.trim()
    }

    @NonCPS
    def parseAndNormalizeJson(String configText) {
        def raw = new JsonSlurper().parseText(configText)
        def normalized = [:]
        raw.each { type, list ->
            normalized[type] = list.collect { item ->
                item instanceof Map ? item.collectEntries { k, v -> [(k): v.toString()] } : item
            }
        }
        return normalized
    }

    @NonCPS
    def findAppType(String repoName, Map parsedMap) {
        parsedMap.find { type, repos ->
            repos.find { it['repo-name'] == repoName }
        }?.key
    }

    String findAvailablePort(int start, int end) {
        def isWindows = !steps.isUnix()

        for (int port = start; port <= end; port++) {
            def cmd = isWindows
                ? "netstat -an | findstr :${port}"
                : "netstat -an | grep :${port}"

            def status = isWindows
                ? steps.bat(script: cmd, returnStatus: true)
                : steps.sh(script: cmd, returnStatus: true)

            if (status != 0) {
                return port.toString()
            }
        }
        return null
    }
}
