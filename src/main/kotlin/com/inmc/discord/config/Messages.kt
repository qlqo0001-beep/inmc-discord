package com.inmc.discord.config

import com.inmc.discord.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `messages.yml` 한 벌. 읽고 보내는 부분은 core 의 [MessageCatalog] 가 갖고 있고 여기는 기본값 표뿐이다.
 *
 * `discord-` 로 시작하는 키는 **디스코드로 보내는 글**이다 — 마크다운 그대로, 색 코드 없이. 나머지는 게임 안 메시지(MiniMessage·&색).
 * `ResourceTest` 가 배포 파일과 이 표의 키가 정확히 같은지 지킨다.
 */
class Messages(values: Map<String, String>) : MessageCatalog<Ph>(values, DEFAULTS) {

    /** 디스코드로 보낼 글 — 토큰만 바꾼다. 마크업을 해석하지 않는다. */
    fun discord(key: String, ph: Ph? = null): String = ph?.apply(raw(key)) ?: raw(key)

    companion object {

        fun from(config: YamlConfiguration): Messages = Messages(merge(DEFAULTS, config))

        val DEFAULTS: Map<String, String> = linkedMapOf(
            PREFIX to "<gradient:#7289da:#99aab5>[ 디스코드 ]</gradient> ",

            // --- 공통 ---------------------------------------------------------------
            "player-only" to "<red>플레이어만 쓸 수 있습니다.</red>",
            "no-permission" to "<red>권한이 없습니다.</red>",
            "reloaded" to "<green>다시 불러왔습니다.</green>",
            "bot-offline" to "<red>디스코드 봇이 연결되어 있지 않습니다.</red>",

            // --- /디스코드 -----------------------------------------------------------
            "invite" to "&b저희 서버 디스코드에 들어와 주세요! <click:open_url:'{value}'><u>{value}</u></click>",
            "invite-none" to "<gray>디스코드 초대 링크가 정해지지 않았습니다.</gray>",

            // --- 계정 연결 (게임) -----------------------------------------------------
            "code-generated" to "링크 코드는 <click:copy_to_clipboard:'{code}'><hover:show_text:'클릭하여 복사'><aqua><b>{code}</b></aqua></hover></click> 입니다. 봇(<aqua>{bot}</aqua>)에게 개인 메세지로 해당 코드를 보내주세요! <gray>({count}분 동안)</gray>",
            "linked-now" to "&b당신의 UUID 는 Discord 사용자 {user} ({id}) 에게 연동되었습니다!",
            "already-linked" to "&b당신의 마인크래프트 계정은 디스코드 계정과 이미 연결되어 있습니다.",
            "linked-status" to "&b당신의 마인크래프트 계정은 디스코드 계정 {name}과 연결되어 있습니다.",
            "unlinked" to "&b당신의 마인크래프트 계정은 더 이상 {name}과 연결되어있지 않습니다.",
            "not-linked" to "&c당신의 마인크래프트 계정은 디스코드 계정과 연결되어있지 않습니다.",

            // --- 디스코드 → 게임 --------------------------------------------------------
            "attachment" to "&e[&b{name}&e]",
            "attachment-hover" to "&b눌러서 미리보기",
            "attachment-link" to " &a(원본)",
            "attachment-link-hover" to "&e눌러서 원본 열기",
            "preview-title" to "{name}",
            "preview-loading" to "<gray>그림을 불러오는 중…</gray>",
            "preview-expired" to "<red>이 그림은 만료되었습니다.</red>",
            "preview-not-image" to "<red>게임에서 미리 볼 수 없는 파일입니다. (원본) 을 눌러 여세요.</red>",
            "preview-failed" to "<red>그림을 불러오지 못했습니다.</red>",

            // --- 디스코드에서 공유(/아이템 · /인벤 · /엔더) — {item} 은 누르면 보기 화면 ----------------
            "share-item" to "&6{player} 님이 디스코드에서 아이템을 공유했습니다: &f{item}",
            "share-inventory" to "&6{player} 님이 디스코드에서 가방을 공유했습니다: &f{item}",
            "share-ender" to "&6{player} 님이 디스코드에서 엔더 상자를 공유했습니다: &f{item}",

            // --- 채팅 꾸미기 -----------------------------------------------------------
            "view-expired" to "<red>이 보기 화면은 만료되었습니다!</red>",
            "empty-hand" to "<red>보일 아이템이 없습니다! 손에 무언가를 들어 주세요.</red>",
            "placeholder-list-header" to "<yellow>채팅 자리표시 목록:</yellow>",
            "placeholder-list-line" to "<aqua>{count}.</aqua> \"<white>{name}</white>\" <gold>-</gold> {value}",

            // --- 관리 ---------------------------------------------------------------
            "status" to listOf(
                "<gold>디스코드 상태</gold>",
                "<gray>봇:</gray> {value}",
                "<gray>연결 계정:</gray> <white>{count}명</white>",
                "<gray>콘솔 대기 줄:</gray> <white>{name}</white>",
                "<gray>아이템 그림:</gray> {bot}",
            ).joinToString("\n"),

            "verify-start" to "<gray>디스코드 검증을 시작합니다…</gray>",
            "verify-ok" to "<green> ✔ {name}</green> <gray>{value}</gray>",
            "verify-fail" to "<red> ✘ {name}</red> <gray>— {value}</gray>",
            "verify-warn" to "<yellow> ! {name}</yellow> <gray>— {value}</gray>",
            "verify-done" to "<gold>디스코드 검증</gold> <gray>— 통과 <green>{count}</green> · 실패 <red>{value}</red></gray>",

            // --- 도움말 -------------------------------------------------------------
            "help" to listOf(
                "<gold>/디스코드</gold> <gray>- 초대 링크</gray>",
                "<gold>/디스코드 연결</gold> <gray>· </gray><gold>/디스코드 연결해제</gold> <gray>- 디스코드 계정 잇기</gray>",
                "<gold>/디스코드 자리표시</gold> <gray>- 채팅에 쓸 수 있는 [item] 같은 것</gray>",
            ).joinToString("\n"),
            // 관리자 줄은 권한이 있을 때만(2026-10-08).
            "help-admin" to listOf(
                "<red>/디스코드 관리 리로드</red> <gray>· </gray><red>/디스코드 관리 상태</red> <gray>· </gray><red>/디스코드 관리 검증 [보내기]</red>",
                "<red>/디스코드 관리 시험 <접속|처음접속|퇴장|사망></red> <gray>· </gray><red>/디스코드 관리 별명 [플레이어]</red>",
            ).joinToString("\n"),

            // --- 디스코드로 보내는 글 --------------------------------------------------
            "discord-unknown-code" to "그런 코드는 발급한 적 없습니다, 다시 시도 해 주세요.",
            "discord-invalid-code" to "코드가 올바르지 않습니다. 코드는 4자리 숫자로 구성 되어 있습니다.",
            "discord-linked" to "{name} (UUID : {uuid})의 연동이 성공하였습니다!",
            "discord-already-linked" to "이미 {name}({uuid}) 에 연결되어 있습니다",
            "discord-list-header" to "**온라인 중인 플레이어 ({count} 명):**",
            "discord-list-empty" to "**현재 온라인인 플레이어가 없습니다.**",
            "discord-list-separator" to ", ",
            "discord-command-error" to "**{user}님,** 명령어 실행중 오류가 발생하였습니다. 에러 코드: {error}",
            "discord-command-no-role" to "권한 없음",
            "discord-command-not-allowed" to "허용되지 않은 명령어 ({command})",
            "discord-command-blocked" to "콘솔 채널에서 막아 둔 명령어입니다: `{command}`",
            "discord-console-dropped" to "... (줄이 너무 많아 {count}줄을 건너뜀)",
            "discord-item-title" to "{player} 님의 아이템",
            "discord-inventory-title" to "{player} 님의 가방",
            "discord-ender-title" to "{player} 님의 엔더 상자",
            "discord-empty" to "(비어 있음)",
            "discord-and-more" to "... 외 {count}개",
            "discord-select-slot" to "칸을 골라 자세히 보기",
            "discord-expired" to "보기가 만료되었습니다. 게임에서 다시 보여 주세요.",
            "discord-render-failed" to "그림을 그리지 못했습니다.",
            "discord-button-contents" to "내용 보기",
            "discord-button-book" to "책 보기",
            "discord-button-map" to "지도 보기",
            "discord-button-prev" to "◀",
            "discord-button-next" to "▶",
            "discord-slot-hotbar" to "단축바 {count}",
            "discord-slot-bag" to "가방 {count}",
            "discord-slot-head" to "투구",
            "discord-slot-chest" to "갑옷",
            "discord-slot-legs" to "바지",
            "discord-slot-feet" to "신발",
            "discord-slot-offhand" to "왼손",
            "discord-slot-ender" to "칸 {count}",
            "discord-not-linked" to "마인크래프트 계정과 연결되어 있지 않습니다. 게임에서 `/디스코드 연결` 을 해 주세요.",
            "discord-player-offline" to "{name} 님은 지금 접속해 있지 않습니다.",
            "discord-no-permission" to "권한이 없습니다.",
            "discord-unknown-player" to "그런 플레이어를 찾지 못했습니다.",
            "discord-info-linked" to "디스코드: {user}",
            "discord-verify-message" to "inmc-discord 검증 메시지입니다 — 10초 뒤 지워집니다.",
        )
    }
}
