# Security overlays for OpenVPN 2.7.9, commit
# 7b60c2aa34992639691e3a2d651fe27301e2e9d5. Generated C sources retain
# their upstream GPL-2.0 notices; cached upstream sources remain unmodified.
# Include after defining OPENVPN2_SOURCE_DIR and OPENVPN2_OVERLAY_DIR.

foreach(required OPENVPN2_SOURCE_DIR OPENVPN2_OVERLAY_DIR)
    if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
        message(FATAL_ERROR "${required} must be defined for OpenVPN 2 security overlays")
    endif()
endforeach()

function(nuvio_openvpn2_overlay name expected_hash before after)
    set(input "${OPENVPN2_SOURCE_DIR}/src/openvpn/${name}")
    file(READ "${input}" contents)
    # Git on Windows may use CRLF in its checkout; compare the pinned LF blob
    # and generate the same C source without changing cached checkout files.
    string(REPLACE "\r\n" "\n" contents "${contents}")
    string(SHA256 actual_hash "${contents}")
    if(NOT actual_hash STREQUAL expected_hash)
        message(FATAL_ERROR "OpenVPN 2 ${name} differs from the reviewed 2.7.9 source")
    endif()
    string(FIND "${contents}" "${before}" start)
    if(start EQUAL -1)
        message(FATAL_ERROR "OpenVPN 2 ${name} security overlay did not match")
    endif()
    string(REPLACE "${before}" "${after}" contents "${contents}")
    file(MAKE_DIRECTORY "${OPENVPN2_OVERLAY_DIR}")
    file(WRITE "${OPENVPN2_OVERLAY_DIR}/${name}" "${contents}")
endfunction()

# Upstream sends the descriptor to Android but ignores a refused protection
# request. Never connect a transport whose protection could not be applied.
nuvio_openvpn2_overlay(socket.c
    aa553993eda6ab547ffe45296a4efb10f0cf14967b231ff1ca064e7b3762ea81
    [=[    management_android_control(management, "PROTECTFD", __func__);]=]
    [=[    if (!management_android_control(management, "PROTECTFD", __func__))
    {
        msg(M_FATAL, "Android transport socket protection failed");
    }]=])

# Several Android setup callers ignore the helper's boolean return value. A
# refused or unanswered request must terminate instead of continuing with only
# part of the requested setup. PERSIST_TUN_ACTION uses a separate helper and
# keeps its explicit OPEN_BEFORE_CLOSE response.
nuvio_openvpn2_overlay(manage.c
    8f19078d45041bbc847f58298a08e7d1c8cfe8144831d5b985b599c19c756a60
    [=[    management_query_user_pass(management, &up, command, GET_USER_PASS_NEED_OK, (void *)0);
    return strcmp("ok", up.password) == 0;]=]
    [=[    const bool answered = management_query_user_pass(management, &up, command,
                                                     GET_USER_PASS_NEED_OK, (void *)0);
    if (!answered || strcmp("ok", up.password) != 0)
    {
        msg(M_FATAL, "Android refused the requested tunnel setup");
    }
    return true;]=])

# The Android management protocol carries plain DNS addresses only. Upstream's
# conversion silently skips secure DNS servers and can select a plain backup.
# Inspect every server before that conversion, including after a plain server,
# and reject DNS features neither the app TUN nor the private stack implements.
# This function is used for both local settings and server-pushed settings.
nuvio_openvpn2_overlay(options.c
    65eb1a567e9725180060be762e273cc8c48cbf318eede08d1fc6fbfc3edd33e7
    [=[    struct dns_options *dns = &o->dns_options;
    struct tuntap_options *tt = &o->tuntap_options;
    if (!dns->servers)]=]
    [=[    struct dns_options *dns = &o->dns_options;
    struct tuntap_options *tt = &o->tuntap_options;
#if defined(TARGET_ANDROID)
    if (dns->search_domains || dns->from_dhcp.domain
        || dns->from_dhcp.domain_search_list_len)
    {
        msg(M_FATAL, "Android compatibility engine does not support DNS search domains");
    }
    for (const struct dns_server *server = dns->servers; server; server = server->next)
    {
        if (server->domains || server->sni
            || (server->transport != DNS_TRANSPORT_UNSET
                && server->transport != DNS_TRANSPORT_PLAIN)
            || (server->dnssec != DNS_SECURITY_UNSET && server->dnssec != DNS_SECURITY_NO))
        {
            msg(M_FATAL, "Android compatibility engine cannot apply the requested DNS protection");
        }
        unsigned int ipv4_count = 0, ipv6_count = 0;
        for (size_t i = 0; i < server->addr_count; ++i)
        {
            if (server->addr[i].port && server->addr[i].port != 53)
            {
                msg(M_FATAL, "Android compatibility engine requires standard DNS ports");
            }
            if (server->addr[i].family == AF_INET)
            {
                ++ipv4_count;
            }
            else if (server->addr[i].family == AF_INET6)
            {
                ++ipv6_count;
            }
            else
            {
                msg(M_FATAL, "Android compatibility engine received an invalid DNS address family");
            }
        }
        // Keep the existing conversion below from truncating a family or
        // treating an overflowing IPv4 address as the IPv6 union member.
        if (ipv4_count >= N_DHCP_ADDR || ipv6_count >= N_DHCP_ADDR)
        {
            msg(M_FATAL, "Android compatibility engine received too many DNS addresses");
        }
    }
#endif
    if (!dns->servers)]=])
