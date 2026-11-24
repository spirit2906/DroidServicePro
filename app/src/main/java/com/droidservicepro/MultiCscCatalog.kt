package com.droidservicepro

/**
 * Public reference snapshots for common Samsung multi-CSC bundles.
 * Bundle membership can vary by model and firmware; firmware-resident lists take priority.
 */
internal object MultiCscCatalog {
    private val entries = mapOf(
        "ODD" to "INU INS ETR TML SLK NPL",
        "OJK" to "ILO MIR PCL PTR",
        "OJP" to "BTC EGY JED KOR KSA MAT MED MID MMV MWD PAK THR WAN XSG XSS",
        "OJV" to "ABS ACR AFG AFR ARB BTC ECT EGY FWD JED KEN KSA LYS MAT MED MID MMC MRT MWD PAK THR TMC TUN TUR WAN WTL XFA XFC XFE XFM XFV XSG",
        "OLB" to "GLB MM1 SIN SMA STH THL XEV XME XSE XSP XTC XTE XXV",
        "OLC" to "MM1 SIN STH XSP",
        "OWE" to "ANC CHE DOR CWW BAT",
        "OWO" to "ARO BVO BVT CDR CGU CHL CHT COB COL COM COO CPA CRC CTI CTU EBE ECO IUS MXO NBS PEO PET PGU PNT PSN PSP SAM TCE TGP TMM TPA UFN UFU UPO ZTA ZTM ZTO ZVV",
        "OXA" to "BTU CPW DBT ITV KOR XEF XEO",
        "OXE" to "CAC RTL SEK SER SKZ",
        "OXF" to "ALB BGL CMF ORL SEB TMT TOP TRA XEH",
        "OXI" to "ITV OMN",
        // Union of the public 2025 OXM table and the 66-code SamFw 6.3 list supplied for this project.
        "OXM" to "ACR AFG AFR AUT BKD BOG BTB BTE BTU CAU CPW DBT DKR ECT EGY EUX EUY EVR FWD GLB ILO ILP INS KSA LYS MET MEO MID MM1 MOB MOT MSR MWD NPL O2C OPS PKD PLS PRT SEK SER SFR SIM SIN SKZ SLK STH SWC TEL THL TIM TOP TSI TUN TUR VAU VIP WWA WWC WWD XEF XFA XFE XFV XME XNZ XSA XSG XSP XTC XXV",
        "OXX" to "BGL CYO ERO EUR MSR ORS PHE SEE SIO TPH TSR TWO VVT XEF XEO XEZ XSK",
        "OYA" to "BMC BWA FMC KDO MTA PCM RWC SOL SPC TLS VMC",
        "OYM" to "XAA",
        "OYN" to "ATT VZW TMB USC XAA",
        "OZS" to "TGY BRI",
        "UWA" to "CDR PCT",
        "UVC" to "CTI CTP CTU",
        "UVY" to "IUS UNE",
        "VFG" to "ATL CNX CYV MOB MTL OMN PRO SFR SIM SWC TCL TOP VD2 VDC VDF VDH VDI VDP VDR VGR VIP VOD VOP",
        "VFR" to "ATL OMN VD2 VDF VOD"
    ).mapValues { (_, codes) -> codes.split(' ').toSet() }

    fun codesFor(multiCsc: String?): List<String> =
        entries[multiCsc?.uppercase()]?.sorted().orEmpty()

    fun hasGroup(multiCsc: String?): Boolean = !codesFor(multiCsc).isEmpty()
}
