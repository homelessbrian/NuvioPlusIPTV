/* SPDX-License-Identifier: GPL-2.0-only
 * This launcher belongs to the separate OpenVPN 2 compatibility executable.
 */
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <unistd.h>

extern int nuvio_openvpn2_engine_main(int argc, char **argv);

int main(int argc, char **argv)
{
    /* The Java process is the lifecycle owner. Do not leave an authenticated
     * tunnel running after that owner dies, including the prctl race window. */
    pid_t parent = getppid();
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent)
    {
        return 125;
    }
    struct rlimit core_limit = {0, 0};
    if (setrlimit(RLIMIT_CORE, &core_limit) != 0 || prctl(PR_SET_DUMPABLE, 0) != 0)
    {
        return 125;
    }
    /* Engine diagnostics can contain provider endpoints and profile material.
     * State and byte counts are read through the private management socket. */
    int quiet = open("/dev/null", O_WRONLY | O_CLOEXEC);
    if (quiet < 0 || dup2(quiet, STDOUT_FILENO) < 0 || dup2(quiet, STDERR_FILENO) < 0)
    {
        return 125;
    }
    close(quiet);
    if (unsetenv("NUVIO_OPENVPN2_VALIDATE_ONLY") != 0)
    {
        return 125;
    }
    if (argc > 1 && strcmp(argv[1], "--nuvio-validate") == 0)
    {
        if (setenv("NUVIO_OPENVPN2_VALIDATE_ONLY", "1", 1) != 0)
        {
            return 125;
        }
        /* The engine installs a cooperative SIGTERM handler before parsing.
         * Bound stdin/parser work even on API 24/25, where Java lacks a forced
         * Process termination API. This process never opens a tunnel. */
        struct sigaction expiry;
        memset(&expiry, 0, sizeof(expiry));
        expiry.sa_handler = SIG_DFL;
        sigemptyset(&expiry.sa_mask);
        sigset_t expiry_mask;
        sigemptyset(&expiry_mask);
        sigaddset(&expiry_mask, SIGALRM);
        if (sigaction(SIGALRM, &expiry, NULL) != 0
            || sigprocmask(SIG_UNBLOCK, &expiry_mask, NULL) != 0)
        {
            return 125;
        }
        alarm(15);
        /* Keep argv[0], removing only the launcher's private first flag. */
        for (int i = 1; i < argc; ++i)
        {
            argv[i] = argv[i + 1];
        }
        --argc;
    }
    return nuvio_openvpn2_engine_main(argc, argv);
}
