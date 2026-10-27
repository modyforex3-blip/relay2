/**
 * GSM Cloud Relay - Polite & Resilient Cloudflare Tunnel Guardian (Anti-Flood 24/7)
 *
 * Designed to ensure the communication server is always available:
 * 1. Spawns cloudflared cleanly without port/socket conflicts.
 * 2. Parses and extracts the public HTTPS URL in real-time.
 * 3. Saves the live URL to `current_tunnel_url.txt` and syncs with web-terminal & DEPLOY_TO_RENDER.
 * 4. Respectful Keep-Alive: Gentle 120s health checks (zero flooding, no abuse penalties).
 * 5. Exponential backoff on unexpected exits (10s -> 20s -> 45s -> 60s) to strictly prevent hammering.
 * 6. Never kills a running tunnel process due to temporary latency or single failed ping.
 */

const { spawn, execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const https = require('https');
const dns = require('dns');

// Reliable DNS resolvers
try {
    dns.setServers(['1.1.1.1', '8.8.8.8', '1.0.0.1']);
} catch (e) {}

const CLOUDFLARED_BIN = path.join(__dirname, 'cloudflared.exe');
const LOCAL_TARGET = 'http://127.0.0.1:5050';
const URL_FILE = path.join(__dirname, 'current_tunnel_url.txt');
const WEB_URL_FILE = path.join(__dirname, 'web-terminal', 'current_tunnel_url.txt');
const DEPLOY_URL_FILE = path.join(__dirname, 'DEPLOY_TO_RENDER', 'current_tunnel_url.txt');

const PING_INTERVAL_MS = 120000; // 2 minutes gentle check (compliant & non-flooding)

let currentChild = null;
let currentTunnelUrl = null;
let pingTimer = null;
let restartTimeout = null;
let restartAttempts = 0;
let stableTimer = null;

function log(msg) {
    const timestamp = new Date().toLocaleTimeString('ar-EG', { hour12: false });
    console.log(`[${timestamp}] [TunnelGuardian] ${msg}`);
}

function killOrphanProcesses() {
    try {
        if (process.platform === 'win32') {
            execSync('taskkill /f /im cloudflared.exe', { stdio: 'ignore' });
        }
    } catch (e) {
        // Ignored - process may not exist
    }
}

function saveTunnelUrl(url) {
    currentTunnelUrl = url;
    try {
        fs.writeFileSync(URL_FILE, url, 'utf8');
        if (fs.existsSync(path.dirname(WEB_URL_FILE))) fs.writeFileSync(WEB_URL_FILE, url, 'utf8');
        if (fs.existsSync(path.dirname(DEPLOY_URL_FILE))) fs.writeFileSync(DEPLOY_URL_FILE, url, 'utf8');
        log(`💾 تم حفظ وتثبيت الرابط النشط بنجاح: ${url}`);
    } catch (e) {
        log(`خطأ في حفظ ملف الرابط: ${e.message}`);
    }
}

function scheduleRestart() {
    if (restartTimeout) return;

    restartAttempts++;
    // Exponential backoff: 10s, 20s, 40s, max 60s
    const delayMs = Math.min(60000, 10000 * Math.pow(1.5, Math.min(restartAttempts - 1, 4)));

    log(`⏳ سيتم إعادة تشغيل النفق بعد ${Math.round(delayMs / 1000)} ثانية لمنع أي ضغط (محاولة: ${restartAttempts})...`);
    restartTimeout = setTimeout(() => {
        restartTimeout = null;
        startTunnel();
    }, delayMs);
}

function startTunnel() {
    if (restartTimeout) {
        clearTimeout(restartTimeout);
        restartTimeout = null;
    }
    if (pingTimer) {
        clearInterval(pingTimer);
        pingTimer = null;
    }
    if (stableTimer) {
        clearTimeout(stableTimer);
        stableTimer = null;
    }

    if (currentChild) {
        try {
            currentChild.removeAllListeners();
            currentChild.kill();
        } catch (e) {}
        currentChild = null;
    }

    killOrphanProcesses();

    log(`🚀 جاري تشغيل نفق Cloudflare المباشر إلى ${LOCAL_TARGET} (بدون فلود وباحترام الحصص)...`);

    try {
        currentChild = spawn(CLOUDFLARED_BIN, ['tunnel', '--url', LOCAL_TARGET, '--no-autoupdate'], {
            stdio: ['ignore', 'pipe', 'pipe']
        });
    } catch (err) {
        log(`❌ فشل استدعاء cloudflared: ${err.message}`);
        scheduleRestart();
        return;
    }

    let detected = false;

    function handleOutput(data) {
        const text = data.toString();

        const match = text.match(/https:\/\/([a-zA-Z0-9-]+)\.trycloudflare\.com/);
        if (match && !detected && match[1] !== 'api') {
            detected = true;
            const newUrl = match[0];
            log(`🎉 تم استخراج رابط النفق المباشر بنجاح: ${newUrl}`);
            saveTunnelUrl(newUrl);
            startKeepAlive(newUrl);

            // Once the tunnel has run stably for 90s, reset restart backoff
            stableTimer = setTimeout(() => {
                restartAttempts = 0;
                log(`✅ النفق يعمل باستقرار تام وتم تصفير عداد المحاولات.`);
            }, 90000);
        }

        if (text.includes('Unauthorized: Tunnel not found')) {
            log(`⚠️ تم إغلاق النفق القديم من جهة الخادم. جاري الجدولة الهادئة...`);
            scheduleRestart();
        }
    }

    currentChild.stdout.on('data', handleOutput);
    currentChild.stderr.on('data', handleOutput);

    currentChild.on('error', (err) => {
        log(`❌ خطأ في عملية cloudflared: ${err.message}`);
        currentChild = null;
        scheduleRestart();
    });

    currentChild.on('exit', (code, signal) => {
        log(`🛑 توقفت عملية cloudflared (Code: ${code}, Signal: ${signal})`);
        currentChild = null;
        scheduleRestart();
    });
}

function startKeepAlive(url) {
    if (pingTimer) clearInterval(pingTimer);

    log(`💓 تم تفعيل فحص الصحة الدوري الهادئ (كل دقيقتين لمنع أي حمل زائد)...`);

    // First check after 15 seconds
    setTimeout(() => {
        doPing(url);
    }, 15000);

    pingTimer = setInterval(() => {
        doPing(url);
    }, PING_INTERVAL_MS);
}

function doPing(url) {
    if (!url || restartTimeout || !currentChild) return;

    const pingUrl = `${url}/api/telemetry`;
    const start = Date.now();

    const req = https.get(pingUrl, { timeout: 15000 }, (res) => {
        const latency = Date.now() - start;
        if (res.statusCode >= 200 && res.statusCode < 400) {
            log(`💓 حالة السيرفر والنفق: متاح ومستقر (استجابة: ${latency}ms)`);
        } else {
            log(`ℹ️ فحص دوري: رمز الاستجابة ${res.statusCode}`);
        }
        res.resume();
    });

    req.on('error', (err) => {
        // Do NOT kill cloudflared on temporary network latency
        log(`ℹ️ ملاحظة مؤقتة في فحص النفق: ${err.message} (العملية مستمرة تلقائياً)`);
    });

    req.on('timeout', () => {
        req.destroy();
        log(`ℹ️ مهلة الفحص الدوري انتهت (سيستمر النفق دون انقطاع)`);
    });
}

process.on('uncaughtException', (err) => {
    log(`⚠️ تنبيه: ${err.message}`);
    scheduleRestart();
});

process.on('SIGINT', () => {
    log('إغلاق حارس النفق...');
    killOrphanProcesses();
    process.exit(0);
});

process.on('SIGTERM', () => {
    log('إنهاء حارس النفق...');
    killOrphanProcesses();
    process.exit(0);
});

log('🛡️ بدء تشغيل حارس النفق السحابي الذكي (Anti-Flood High-Stability Guardian)...');
startTunnel();
