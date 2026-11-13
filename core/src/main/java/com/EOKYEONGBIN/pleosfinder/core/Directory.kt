package com.EOKYEONGBIN.pleosfinder.core

/** 연락처에 있는 사람. hasFinderPhone = 폰에 플레오스 Finder 를 설치했는지(사람 아이콘 표시 기준). */
data class Account(
    val member: Member,
    val hasFinderPhone: Boolean,
    /** 연락처에 보이는 설명(예: 직장 동료) */
    val note: String,
)

/** 저장해 둔 장소(집, 여행지). 기획상 내비 즐겨찾기(집·회사)에서 가져오고, 출품본은 시연용으로 고정한다. */
data class SavedPlace(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

/** Pleos 차량. 한 차량을 여러 사람이 운전할 수 있고, 위치 공유는 그때 운전하는 사람의 설정을 따른다. */
data class VehicleInfo(
    val id: String,
    val name: String,
    val ownerId: String,
    val driverIds: List<String>,
)

/**
 * 시연용 연락처·차량 목록. 기획상 Pleos 계정과 연락처에서 가져오지만,
 * 출품본은 계정 연동을 할 수 없어 고정 목록을 쓴다.
 */
object DemoDirectory {
    val DAD = Member(id = "dad", name = "아빠")
    val MOM = Member(id = "mom", name = "엄마")
    val SON = Member(id = "son", name = "아들")
    val MINSU = Member(id = "minsu", name = "김민수")
    val JIHOON = Member(id = "jihoon", name = "박지훈")
    val SEOYEON = Member(id = "seoyeon", name = "이서연")

    val accounts = listOf(
        Account(DAD, hasFinderPhone = true, note = "가족"),
        Account(MOM, hasFinderPhone = true, note = "가족"),
        Account(SON, hasFinderPhone = true, note = "가족"),
        Account(MINSU, hasFinderPhone = true, note = "친구"),
        Account(JIHOON, hasFinderPhone = false, note = "직장 동료"),
        Account(SEOYEON, hasFinderPhone = true, note = "친구"),
    )

    /** 이 에뮬레이터가 시연하는 차량 */
    val IONIQ5 = VehicleInfo("ioniq5", "아이오닉5", ownerId = DAD.id, driverIds = listOf(DAD.id, MOM.id))
    val GV70 = VehicleInfo("gv70", "GV70", ownerId = MINSU.id, driverIds = listOf(MINSU.id))
    val K8 = VehicleInfo("k8", "K8", ownerId = JIHOON.id, driverIds = listOf(JIHOON.id))

    val vehicles = listOf(IONIQ5, GV70, K8)

    /**
     * 시연 무대: 발표 장소를 아빠의 회사로 가정한다. 에뮬레이터 차량 위치가 고정된 곳이
     * 판교 제2테크노밸리(포티투닷 본사 앞)라, 아빠는 회사에서 출발해 판교역의 아들을 태우고 집으로 간다.
     * 여행은 집에서 강릉 경포해변으로 간다.
     */
    val HOME = SavedPlace("우리 집 (판교원마을 5단지)", "경기 성남시 분당구 판교원로", 37.38725, 127.08978)
    val GANGNEUNG = SavedPlace("강릉 경포해변", "강원 강릉시 해안로 (경포동)", 37.80448, 128.90530)
    val savedPlaces = listOf(HOME, GANGNEUNG)

    /** 연락처의 이름·호칭(최근 목적지 중 지난 픽업 대상을 걸러 내는 데 쓴다) */
    val contactNames: Set<String>
        get() = accounts.map { it.member.name }.toSet() + RELATIVE_NAMES.values

    /** 보는 사람 기준 호칭. 계정 이름(아빠·엄마·아들)은 아들 기준이라, 부부끼리는 서로 와이프·남편으로 부른다. */
    private val RELATIVE_NAMES = mapOf(
        (DAD.id to MOM.id) to "와이프",
        (MOM.id to DAD.id) to "남편",
    )

    fun nameFor(viewerId: String, id: String): String = RELATIVE_NAMES[viewerId to id] ?: member(id).name

    /** viewerId 가 부르는 이름을 담은 Member */
    fun memberFor(viewerId: String, id: String): Member = Member(id, nameFor(viewerId, id))

    fun account(id: String): Account? = accounts.firstOrNull { it.member.id == id }

    fun member(id: String): Member = account(id)?.member ?: Member(id, id)

    fun hasFinderPhone(id: String) = account(id)?.hasFinderPhone == true

    /** 이 사람이 운전자로 등록된 Pleos 차량(차량 아이콘 표시 기준) */
    fun vehiclesDrivenBy(id: String): List<VehicleInfo> = vehicles.filter { id in it.driverIds }

    fun vehicle(id: String): VehicleInfo? = vehicles.firstOrNull { it.id == id }
}
