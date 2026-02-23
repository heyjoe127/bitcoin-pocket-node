package com.pocketnode.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

/**
 * Shared SSH/SFTP utilities used by ChainstateManager and BlockFilterManager.
 */
object SshUtils {

    fun connectSsh(host: String, port: Int, user: String, password: String): Session {
        val jsch = JSch()
        val session = jsch.getSession(user, host, port)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(15_000)
        return session
    }

    // Uses sudo -S (read password from stdin) because SSH pseudo-terminal allocation
    // is unreliable with JSch. Piping the password via stdin avoids TTY requirements
    // and works on all server configurations (Umbrel, Start9, vanilla Linux).
    fun execSudo(session: Session, sudoPass: String, command: String,
                 timeoutMs: Long = 60_000): String {
        // Each call opens a fresh exec channel because JSch exec channels are single-use --
        // they cannot be reused after the remote command exits.
        val channel = session.openChannel("exec") as ChannelExec
        val escaped = command.replace("'", "'\\''")
        channel.setCommand("sudo -S bash -c '$escaped' 2>&1")
        val output = ByteArrayOutputStream()
        channel.outputStream = output
        channel.setInputStream((sudoPass + "\n").toByteArray().inputStream())
        channel.connect(30_000)
        val start = System.currentTimeMillis()
        while (!channel.isClosed && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(500)
        }
        val result = output.toString("UTF-8")
            .replace(Regex("\\[sudo\\][^\n]*?:\\s*"), "")
        channel.disconnect()
        return result
    }

    fun exec(session: Session, command: String, timeoutMs: Long = 30_000): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val output = ByteArrayOutputStream()
        channel.outputStream = output
        channel.connect(15_000)
        val start = System.currentTimeMillis()
        while (!channel.isClosed && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(500)
        }
        val result = output.toString("UTF-8")
        channel.disconnect()
        return result
    }

    /**
     * Find the Bitcoin data directory on a remote node.
     * Handles Docker (Umbrel, Start9) and native installs.
     */
    fun findBitcoinDataDir(session: Session, sshPassword: String): String {
        val isDocker = execSudo(session, sshPassword,
            "docker ps 2>/dev/null | grep -qi bitcoin && echo 'DOCKER' || echo 'NATIVE'")
            .trim().lines().lastOrNull()?.trim() == "DOCKER"

        return if (isDocker) {
            val containerName = detectDockerContainer(session, sshPassword)
            if (containerName != null) {
                val mountInfo = execSudo(session, sshPassword,
                    "docker inspect $containerName --format '{{range .Mounts}}{{if or (eq .Destination \"/data/.bitcoin\") (eq .Destination \"/data/bitcoin\") (eq .Destination \"/bitcoin/.bitcoin\")}}{{.Source}}{{end}}{{end}}' 2>/dev/null")
                    .trim().lines().lastOrNull()?.trim() ?: ""
                if (mountInfo.isNotEmpty()) {
                    val hasChainstate = execSudo(session, sshPassword,
                        "test -d '$mountInfo/chainstate' && echo 'YES' || echo 'NO'")
                        .trim().lines().lastOrNull()?.trim()
                    if (hasChainstate == "YES") mountInfo
                    else {
                        val withBitcoin = "$mountInfo/bitcoin"
                        val check = execSudo(session, sshPassword,
                            "test -d '$withBitcoin/chainstate' && echo 'YES' || echo 'NO'")
                            .trim().lines().lastOrNull()?.trim()
                        if (check == "YES") withBitcoin else findBySearch(session, sshPassword)
                    }
                } else findBySearch(session, sshPassword)
            } else findBySearch(session, sshPassword)
        } else findBySearch(session, sshPassword)
    }

    fun detectDockerContainer(session: Session, sshPassword: String): String? {
        val result = execSudo(session, sshPassword,
            "docker ps --format '{{.Names}}' 2>/dev/null | grep -i bitcoin | grep -vi 'proxy\\|tor\\|i2p\\|lnd\\|cln' | head -1")
            .trim().lines().lastOrNull()?.trim()
        return if (result.isNullOrEmpty()) null else result
    }

    /**
     * LND detection result. Holds the info needed to interact with LND on the remote node.
     */
    data class LndInfo(
        val nodeOs: String,           // "umbrel", "citadel", "raspiblitz", "mynode", "start9", "native"
        val lncliPrefix: String,      // Full command prefix to run lncli (e.g. "docker exec lightning lncli")
        val confPath: String?,        // Path to lnd.conf (null if unknown)
        val isDocker: Boolean
    )

    /**
     * Detect LND on the remote node. Probes for known node OS types and finds
     * the correct lncli invocation. Returns null if no LND is found.
     */
    fun detectLnd(session: Session, sshPassword: String): LndInfo? {
        // Check for Umbrel LND container
        val umbrelLnd = execSudo(session, sshPassword,
            "docker ps --format '{{.Names}}' 2>/dev/null | grep -iE '_lnd_|^lnd\$|^lnd_|umbrel.*lnd' | head -1")
            .trim().lines().lastOrNull()?.trim()
        if (!umbrelLnd.isNullOrEmpty()) {
            // Verify lncli works inside the container
            val test = execSudo(session, sshPassword,
                "docker exec $umbrelLnd lncli getinfo 2>/dev/null | head -1")
                .trim().lines().lastOrNull()?.trim() ?: ""
            if (test.contains("{") || test.contains("identity_pubkey")) {
                // Determine node OS
                val nodeOs = when {
                    execSudo(session, sshPassword, "test -d ~/umbrel && echo YES")
                        .trim().lines().lastOrNull()?.trim() == "YES" -> "umbrel"
                    execSudo(session, sshPassword, "test -d ~/citadel && echo YES")
                        .trim().lines().lastOrNull()?.trim() == "YES" -> "citadel"
                    else -> "docker"
                }
                // Find LND conf path via Docker mount
                val confMount = execSudo(session, sshPassword,
                    "docker inspect $umbrelLnd --format '{{range .Mounts}}{{.Source}}:{{.Destination}} {{end}}' 2>/dev/null")
                    .trim()
                val lndDataDir = confMount.split(" ").firstOrNull { it.contains("/lnd") || it.contains("/.lnd") }
                    ?.split(":")?.firstOrNull()
                val confPath = if (lndDataDir != null) "$lndDataDir/lnd.conf" else null

                return LndInfo(nodeOs, "docker exec $umbrelLnd lncli", confPath, true)
            }
        }

        // Check for native lncli (RaspiBlitz, myNode, manual installs)
        val nativeTest = execSudo(session, sshPassword,
            "which lncli 2>/dev/null && lncli getinfo 2>/dev/null | head -1")
            .trim()
        if (nativeTest.contains("identity_pubkey") || nativeTest.contains("{")) {
            val nodeOs = when {
                execSudo(session, sshPassword, "test -f /mnt/hdd/raspiblitz.conf && echo YES")
                    .trim().lines().lastOrNull()?.trim() == "YES" -> "raspiblitz"
                execSudo(session, sshPassword, "test -d /mnt/hdd/mynode && echo YES")
                    .trim().lines().lastOrNull()?.trim() == "YES" -> "mynode"
                else -> "native"
            }
            // Common LND conf locations
            val confPath = listOf(
                "/mnt/hdd/lnd/lnd.conf",
                "/home/bitcoin/.lnd/lnd.conf",
                "~/.lnd/lnd.conf"
            ).firstOrNull { path ->
                execSudo(session, sshPassword, "test -f $path && echo YES")
                    .trim().lines().lastOrNull()?.trim() == "YES"
            }

            return LndInfo(nodeOs, "lncli", confPath, false)
        }

        // Check for Start9
        val start9Test = execSudo(session, sshPassword,
            "which start-cli 2>/dev/null && echo FOUND || echo NOPE")
            .trim().lines().lastOrNull()?.trim()
        if (start9Test == "FOUND") {
            // Start9 uses its own CLI wrapper
            val lndContainer = execSudo(session, sshPassword,
                "docker ps --format '{{.Names}}' 2>/dev/null | grep -i lnd | head -1")
                .trim().lines().lastOrNull()?.trim()
            if (!lndContainer.isNullOrEmpty()) {
                return LndInfo("start9", "docker exec $lndContainer lncli", null, true)
            }
        }

        return null
    }

    /**
     * Check if watchtower server is active on the remote LND node.
     * Returns true if watchtower.active=true in the running config.
     */
    fun isWatchtowerActive(session: Session, sshPassword: String, lndInfo: LndInfo): Boolean {
        val result = execSudo(session, sshPassword,
            "${lndInfo.lncliPrefix} tower info 2>/dev/null")
            .trim()
        // If "tower info" returns valid JSON with pubkey, watchtower is active
        return result.contains("pubkey") && !result.contains("watchtower not active")
    }

    /**
     * Get the watchtower .onion URI from the remote LND node.
     * Returns the full tower URI (pubkey@onion:port) or null if unavailable.
     * Watchtower must already be enabled by the user via their node's UI.
     */
    fun getWatchtowerUri(session: Session, sshPassword: String, lndInfo: LndInfo): String? {
        val result = execSudo(session, sshPassword,
            "${lndInfo.lncliPrefix} tower info 2>/dev/null")
            .trim()

        // Parse the URI from tower info output
        // Format: { "pubkey": "02abc...", "listeners": [...], "uris": ["02abc...@onion:9911"] }
        val uriMatch = Regex(""""uris":\s*\[\s*"([^"]+\.onion:\d+[^"]*)"""").find(result)
        if (uriMatch != null) {
            return uriMatch.groupValues[1]
        }

        // Fallback: try to extract pubkey and build URI from listeners
        val pubkeyMatch = Regex(""""pubkey":\s*"([0-9a-f]+)"""").find(result)
        val onionMatch = Regex("""([a-z2-7]{56}\.onion:\d+)""").find(result)
        if (pubkeyMatch != null && onionMatch != null) {
            return "${pubkeyMatch.groupValues[1]}@${onionMatch.groupValues[1]}"
        }

        return null
    }

    private fun findBySearch(session: Session, sshPassword: String): String {
        return execSudo(session, sshPassword,
            "find / -name 'chainstate' -path '*/bitcoin/*' -type d 2>/dev/null | head -1 | xargs dirname 2>/dev/null")
            .trim().lines().lastOrNull()?.trim() ?: ""
    }
}
