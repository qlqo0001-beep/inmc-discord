plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.discord"
version = "1.0.0"

inmc {
    paper = "26.2"
    pluginName = "inmc-discord"
}

dependencies {
    // 디스코드 봇. 음성 부품(opus-java·tink)은 쓰지 않는다 — 빼면 jar 가 수 MB 줄어든다.
    // slf4j 는 Paper 가 들고 있는 것을 쓴다(셰이딩하면 로그가 두 갈래로 나간다).
    implementation(libs.jda) {
        exclude(module = "opus-java")
        exclude(module = "tink")
        exclude(group = "org.slf4j")
    }

    // 콘솔 채널 — 서버의 log4j 에 어펜더를 단다. 서버가 들고 있으니 컴파일 때만.
    compileOnly(libs.log4j.core)
    testImplementation(libs.log4j.core)
}

tasks.shadowJar {
    // 다른 플러그인(DiscordSRV 등)이 같은 라이브러리의 다른 판을 들고 있어도 섞이지 않게 전부 옮긴다.
    val lib = "com.inmc.discord.lib"
    relocate("net.dv8tion.jda", "$lib.jda")
    relocate("okhttp3", "$lib.okhttp3")
    relocate("okio", "$lib.okio")
    relocate("com.fasterxml.jackson", "$lib.jackson")
    relocate("gnu.trove", "$lib.trove")
    relocate("com.neovisionaries.ws", "$lib.ws")
    relocate("org.apache.commons.collections4", "$lib.collections4")
}
