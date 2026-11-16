// Tap2iD Android SDK External NFC Reference - multibranch CI pipeline.
// Modelled on the Tap2iD-SDK-Android-Sample-Internal pipeline.
//
// Per-branch behavior (driven by the `when { branch }` gates below):
//   - release / main              -> RELEASE apks
//   - development / work branches -> DEBUG apks
// APKs are renamed (version + date + environment + build type) and archived on the build page.
//
// No credentials are needed:
//   - the SDK group repository on the Credence ID Nexus is anonymously readable;
//   - the release build type is signed with the debug key (see app/build.gradle.kts), as this is
//     a reference app for integrators, not a shipped product. Adding a real keystore means binding
//     secret-file credentials around the release stage, as the Sample-Internal pipeline does.
//
// TARGET ENVIRONMENTS: every branch builds one APK per Tap2iD backend - PROD, MIDQA and UAT -
// so QA always has all three from a single run. Each is a separate Gradle invocation with
// -PtargetEnvironment, which app/build.gradle.kts turns into the SDK artifactId
// (tap2idSdk / tap2idSdk-midqa / tap2idSdk-uat) and BuildConfig.TARGET_ENVIRONMENT.
// Unit tests run once, on Gradle's default (UAT): the SDK's public API is identical across the
// three artifacts, so three test runs would test the same code.

// Kept in step with sdkArtifactIdsByEnvironment in app/build.gradle.kts, which rejects anything
// it does not know - a name added here alone fails the build, not silently.
def TARGET_ENVIRONMENTS = ['PROD', 'MIDQA', 'UAT']

// Branches that produce release APKs. There is a single `release` branch (the RC under QA) and
// `main` (the latest official release); both are moved only by the release skills.
// The `when` gates repeat this pattern as a literal: declarative directives are validated
// before script variables exist, so keep the three in step.
def RELEASE_BRANCHES = '^(release|main)$'

pipeline {
    agent any

    options {
        timeout(time: 1, unit: 'HOURS')
    }

    triggers {
        githubPush()
    }

    environment {
        JAVA_HOME    = '/usr/lib/jvm/jdk-17.0.10'
        ANDROID_HOME = '/opt/android-sdk-linux'
    }

    stages {
        stage('Checkout') {
            steps {
                cleanWs()
                checkout scm          // multibranch: checks out the discovered branch
                sh 'chmod +x gradlew'
            }
        }

        stage('Set Build Name') {
            steps {
                script {
                    def buildFile = readFile('app/build.gradle.kts')
                    def match = (buildFile =~ /versionName\s*=\s*"([^"]+)"/)
                    env.APP_VERSION = match ? match[0][1] : 'unknown'

                    env.IS_RELEASE = (env.BRANCH_NAME ==~ RELEASE_BRANCHES) ? 'true' : 'false'
                    currentBuild.displayName = (env.IS_RELEASE == 'true')
                        ? "v${env.APP_VERSION} (#${env.BUILD_NUMBER})"
                        : "v${env.APP_VERSION}-build.${env.BUILD_NUMBER}"

                    if (env.IS_RELEASE == 'true') {
                        currentBuild.keepLog = true   // keep release builds forever
                    }
                }
            }
        }

        stage('Unit Tests') {
            steps {
                sh './gradlew test'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/build/test-results/**/*.xml'
                }
            }
        }

        // development / work branches -> installable DEBUG apks for QA, one per environment.
        stage('Build Debug APKs') {
            when { not { branch pattern: '^(release|main)$', comparator: 'REGEXP' } }
            steps {
                script {
                    for (targetEnv in TARGET_ENVIRONMENTS) {
                        buildApkFor(targetEnv, 'assembleDebug', 'debug')
                    }
                }
            }
        }

        // release / main -> RELEASE apks (minified), one per environment.
        stage('Build Release APKs') {
            when { branch pattern: '^(release|main)$', comparator: 'REGEXP' }
            steps {
                script {
                    for (targetEnv in TARGET_ENVIRONMENTS) {
                        buildApkFor(targetEnv, 'assembleRelease', 'release')
                    }
                }
            }
        }

        stage('Archive APK') {
            steps {
                // An environment whose APK is missing has already failed its own mv.
                archiveArtifacts artifacts: 'release/*.apk', fingerprint: true
            }
        }
    }

    post {
        success { echo "APK built: v${env.APP_VERSION} (#${env.BUILD_NUMBER})" }
        failure { echo 'Build failed' }
    }
}

// Assembles one build type against one Tap2iD backend and renames the APK for both.
//
// The APK is moved OUT of app/build into release/ straight away: the next environment's run
// repackages into the same output directory, and AGP may clear it, which would delete the
// previous environment's APK before the Archive stage saw it.
//
// The Gradle task is passed in rather than derived from buildType: String.capitalize() is a
// Groovy GDK method the Jenkins script sandbox may refuse without an admin approval.
def buildApkFor(String targetEnv, String task, String buildType) {
    def envLower = targetEnv.toLowerCase()
    sh "./gradlew :app:${task} -PtargetEnvironment=${targetEnv} -PversionCode=${env.BUILD_NUMBER}"
    sh """
        mkdir -p release
        mv app/build/outputs/apk/${buildType}/*_${envLower}.apk \
           "release/Tap2iD-NFC-Reference_${env.APP_VERSION}_\$(date +%d%m%y)_${envLower}_${buildType}.apk"
    """
}
