package com.nuvio.tv.core.debrid

/**
 * Release-group tiers from the TRaSH Guides custom formats for Radarr
 * (github.com/TRaSH-Guides/Guides, docs/json/radarr/cf, MIT License, Copyright (c) 2021 TRaSH),
 * as extracted by Nuvio Arc (GPL-3).
 *
 * PREFERRED_LADDER: trusted groups, best first (Remux tiers, UHD BluRay, HD BluRay, WEB).
 * LOW_QUALITY_GROUPS: TRaSH's LQ, bad dual-audio and fake Dynamic HDR lists. By default these
 * are only moved to the bottom of the order, not hidden (see QualityRules).
 * To refresh, re-extract from TRaSH and bump DEFAULTS_VERSION.
 */
object TrashReleaseGroups {

    const val DEFAULTS_VERSION = 1

    val PREFERRED_LADDER: List<String> = listOf(
        // Remux Tier 01
        "3L", "BiZKiT", "BLURANiUM", "BMF", "CiNEPHiLES", "FraMeSToR", "PiRAMiDHEAD", "PmP", "WiLDCAT", "ZQ",
        // Remux Tier 02
        "ATELiER", "NCmt", "playBD", "SiCFoI", "SURFINBIRD", "TEPES",
        // Remux Tier 03
        "12GaugeShotgun", "decibeL", "EPSiLON", "HiFi", "iFT", "KRaLiMaRKo", "NTb", "PTP", "SumVision", "TOA",
        "TRiToN",
        // UHD BluRay Tier 01
        "CtrlHD", "MainFrame", "DON", "W4NK3R",
        // UHD BluRay Tier 02
        "HiDt", "HQMUX",
        // UHD BluRay Tier 03
        "BHDStudio", "hallowed", "HONE", "PTer", "SPHD", "WEBDV",
        // HD BluRay Tier 01
        "BBQ", "c0kE", "Chotab", "CRiSC", "D-Z0N3", "Dariush", "EbP", "EDPH", "Geek", "LolHD", "TayTO", "TDD", "TnP",
        "VietHD", "ZoroSenpai",
        // HD BluRay Tier 02
        "EA", "HiSD", "QOQ", "SA89", "sbR",
        // HD BluRay Tier 03
        "LoRD", "playHD",
        // WEB Tier 01
        "ABBIE", "AJP69", "APEX", "PAXA", "PEXA", "XEPA", "BLUTONiUM", "BYNDR", "CMRG", "CRFW", "CRUD", "FLUX",
        "GNOME", "KiNGS", "Kitsune", "MADSKY", "NOSiViD", "NTG", "RAWR", "SiC", "TheFarm",
        // WEB Tier 02
        "dB", "Flights", "MiU", "monkee", "MZABI", "PHOENiX", "playWEB", "SMURF", "TOMMY", "XEBEC", "4KBEC", "CEBEX",
        // WEB Tier 03
        "BLOOM", "Dooky", "GNOMiSSiON", "HHWEB", "NINJACENTRAL", "NPMS", "ROCCaT", "SiGMA", "SLiGNOME", "SwAgLaNdEr"
    )

    val LOW_QUALITY_GROUPS: List<String> = listOf(
        // LQ
        "24xHD", "41RGB", "4K4U", "AOC", "AROMA", "aXXo", "AZAZE", "BARC0DE", "BAUCKLEY", "BdC", "beAst", "BTM",
        "C1NEM4", "C4K", "CDDHD", "CHAOS", "CHD", "CiNE", "CLEANUP", "COLLECTiVE", "CREATiVE24", "CrEwSaDe", "CTFOH",
        "d3g", "DDR", "DNL", "DRX", "E", "EPiC", "EuReKA", "FaNGDiNG0", "Feranki1980", "FGT", "FMD", "FRDS", "FS",
        "FZHD", "GalaxyRG", "GHD", "GPTHD", "HDHUB4U", "HDS", "HDT", "HDTime", "HDWinG", "iNTENSO", "iPlanet", "iVy",
        "jennaortegaUHD", "jennaortega", "JFF", "KC", "KiNGDOM", "KIRA", "L0SERNIGHT", "LAMA", "Leffe", "Liber8",
        "LiGaS", "LUCY", "MarkII", "MeGusta", "Mesc", "mHD", "mSD", "MTeam", "MT", "MySiLU", "NhaNc3", "nHD",
        "nikt0", "NoGroup", "NoGrp", "nSD", "OFT", "Pahe.ph", "Pahe", "Pahe.in", "PATOMiEL", "PRODJi", "PSA", "PTNK",
        "RARBG", "RBB", "RDN", "Rifftrax", "RU4HD", "SANTi", "Scene", "SHD", "ShieldBearer", "STUTTERSHIT",
        "SUNSCREEN", "SyncUP", "TBS", "TEKNO3D", "Tigole", "TIKO", "VISIONPLUSHDR-X", "VISIONPLUSHDR1000",
        "VISIONPLUSHDR", "WAF", "WiKi", "x0r", "YIFY", "YTS.MX", "YTS", "YTS.LT", "YTS.AG", "Zeus",
        // Bad Dual Groups
        "alfaHD", "BAT", "BlackBit", "BNd", "C.A.A", "C76", "Cory", "CYPHER", "EniaHD", "EXTREME", "FF", "FOXX",
        "G4RiS", "GUEIRA", "LCD", "MGE", "MLH", "N3G4N", "ONLYMOViE", "PD", "PTHome", "RiPER", "RK", "SiGLA", "Tars",
        "TM", "tokar86a", "TURG", "TvR", "vnlls", "WTV", "XiQUEXiQUE", "Yatogam1", "YusukeFLA", "ZigZag", "ZNM",
        // Generated Dynamic HDR
        "BiTOR", "DepraveD", "GuyZo", "BR-GuyZo", "SasukeducK", "tarunk9c", "VD0N", "VECTOR", "VisionXpert"
    )
}
