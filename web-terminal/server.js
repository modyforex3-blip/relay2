const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = process.env.PORT || 5050;
const HTML_FILE = path.join(__dirname, 'index.html');
const DASHBOARD_FILE = path.join(__dirname, 'dashboard.html');
const DOWNLOAD_FILE = path.join(__dirname, 'download.html');
const APK_FILENAME = 'GSM-Cloud-Relay-Gateway-v2.7.1.apk';
const ZIP_FILENAME = 'GSM-Cloud-Relay-Gateway-v2.7.1.zip';
const APK_FILE = fs.existsSync(path.join(__dirname, APK_FILENAME)) 
    ? path.join(__dirname, APK_FILENAME) 
    : (fs.existsSync(path.join(__dirname, '..', APK_FILENAME)) ? path.join(__dirname, '..', APK_FILENAME) : path.join(__dirname, '..', 'GSM-Cloud-Relay-Gateway-v2.6.apk'));
const ZIP_FILE = fs.existsSync(path.join(__dirname, ZIP_FILENAME)) 
    ? path.join(__dirname, ZIP_FILENAME) 
    : (fs.existsSync(path.join(__dirname, '..', ZIP_FILENAME)) ? path.join(__dirname, '..', ZIP_FILENAME) : null);

const startTime = Date.now();
let totalAudioPackets = 0;
let totalCalls = 0;
const recentLogs = [];
const smsHistory = [];

function getTimestamp() {
    return new Date().toLocaleTimeString('ar-EG', { hour12: false });
}

function addAuditLog(msg, type = 'info') {
    const entry = { time: getTimestamp(), msg, type };
    recentLogs.unshift(entry);
    if (recentLogs.length > 50) recentLogs.pop();
    console.log(`[AUDIT] [${entry.time}] ${msg}`);
    broadcastToMonitors({
        type: 'AUDIT_LOG',
        time: entry.time,
        message: msg,
        logType: type
    });
}

// Helper to get local Wi-Fi / LAN IP addresses
function getLocalIps() {
    const interfaces = os.networkInterfaces();
    const ips = [];
    for (const name of Object.keys(interfaces)) {
        for (const net of interfaces[name]) {
            if (net.family === 'IPv4' && !net.internal) {
                ips.push({ name, ip: net.address });
            }
        }
    }
    return ips;
}

// Room-based WebSocket storage
const rooms = new Map(); // roomId -> Set of WebSocket clients
const monitors = new Set(); // Set of dashboard monitors

function findGateway(targetId) {
    for (const [roomId, roomClients] of rooms.entries()) {
        for (const client of roomClients) {
            if (client.clientRole === 'GATEWAY' && client.readyState === WebSocket.OPEN) {
                if (!targetId || client.gatewayId === targetId) {
                    return client;
                }
            }
        }
    }
    return null;
}

function getLiveTunnelUrl() {
    try {
        const urlFile = path.join(__dirname, 'current_tunnel_url.txt');
        const rootUrlFile = path.join(__dirname, '..', 'current_tunnel_url.txt');
        if (fs.existsSync(urlFile)) {
            return fs.readFileSync(urlFile, 'utf8').trim();
        } else if (fs.existsSync(rootUrlFile)) {
            return fs.readFileSync(rootUrlFile, 'utf8').trim();
        }
    } catch (e) {}
    return null;
}

function getTelemetryData() {
    let gatewaysList = [];
    let terminalsList = [];
    let activeCallsCount = 0;
    let activeCallsDetail = [];
    const countedIntercomPairs = new Set();

    for (const [roomId, roomClients] of rooms.entries()) {
        for (const client of roomClients) {
            if (client.clientRole === 'GATEWAY') {
                gatewaysList.push({
                    id: client.gatewayId || 'GW-UNKNOWN',
                    name: client.gatewayName || 'هاتف وسيط GSM',
                    carrier: client.carrier || 'GSM',
                    battery: client.battery !== undefined ? client.battery : null,
                    room: roomId,
                    isBusy: client.isBusy || false,
                    currentCallNumber: client.currentCallNumber || '',
                    callStartTime: client.callStartTime || null
                });
                if (client.isBusy) {
                    activeCallsCount++;
                    activeCallsDetail.push({
                        type: 'GSM',
                        gatewayId: client.gatewayId,
                        gatewayName: client.gatewayName,
                        carrier: client.carrier,
                        room: roomId,
                        number: client.currentCallNumber || 'مكالمة جارية',
                        callStartTime: client.callStartTime || (Date.now() - 5000)
                    });
                }
            } else if (client.clientRole === 'TERMINAL') {
                const ext = client.extension || '#101';
                terminalsList.push({
                    id: client.terminalId || ('TERM-' + ext.replace('#', '')),
                    extension: ext,
                    name: client.terminalName || `هاتف طرفي (${ext})`,
                    room: roomId,
                    isBusy: client.isBusy || false,
                    currentCallNumber: client.currentCallNumber || ''
                });

                if (client.isBusy && client.callType === 'INTERCOM' && client.pairedPeer) {
                    const pairKey = [client.extension, client.pairedPeer.extension].sort().join('<->');
                    if (!countedIntercomPairs.has(pairKey)) {
                        countedIntercomPairs.add(pairKey);
                        activeCallsCount++;
                        activeCallsDetail.push({
                            type: 'INTERCOM',
                            room: roomId,
                            callerExtension: client.extension,
                            targetExtension: client.pairedPeer.extension,
                            number: `اتصال داخلي ${client.extension} ⟷ ${client.pairedPeer.extension}`,
                            callStartTime: client.callStartTime || (Date.now() - 5000)
                        });
                    }
                }
            }
        }
    }

    // Deduplicate gateways by id
    const uniqueGateways = [];
    const seenGwIds = new Set();
    for (const gw of gatewaysList) {
        if (!seenGwIds.has(gw.id)) {
            seenGwIds.add(gw.id);
            uniqueGateways.push(gw);
        }
    }

    // Deduplicate terminals by extension
    const uniqueTerminals = [];
    const seenExts = new Set();
    for (const term of terminalsList) {
        if (!seenExts.has(term.extension)) {
            seenExts.add(term.extension);
            uniqueTerminals.push(term);
        }
    }

    return {
        uptime: Math.floor((Date.now() - startTime) / 1000),
        totalGateways: uniqueGateways.length,
        totalTerminals: uniqueTerminals.length,
        activeCalls: activeCallsCount,
        activeCallsDetail: activeCallsDetail,
        totalAudioPackets: totalAudioPackets,
        totalCalls: totalCalls,
        totalSms: smsHistory.length,
        tunnelUrl: getLiveTunnelUrl(),
        gateways: uniqueGateways,
        terminals: uniqueTerminals,
        smsHistory: smsHistory.slice(0, 50),
        recentLogs: recentLogs.slice(0, 30)
    };
}

function broadcastToMonitors(data) {
    const payload = JSON.stringify(data);
    for (const ws of monitors) {
        if (ws.readyState === WebSocket.OPEN) {
            try {
                ws.send(payload);
            } catch (ignored) {}
        }
    }
}

function notifyTelemetryUpdate() {
    broadcastToMonitors({
        type: 'TELEMETRY_UPDATE',
        data: getTelemetryData()
    });
}

// 1-Second Continuous Live Sync for Telecom Monitors & Dashboards
setInterval(() => {
    if (monitors.size > 0) {
        notifyTelemetryUpdate();
    }
}, 1000);

// HTTP Server
const server = http.createServer((req, res) => {
    // Enable CORS
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');

    const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const pathname = parsedUrl.pathname;

    if (pathname === '/' || pathname === '/dashboard' || pathname === '/dashboard.html' || pathname === '/monitor') {
        fs.readFile(DASHBOARD_FILE, (err, data) => {
            if (err) {
                res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
                res.end('خطأ في تحميل لوحة المتابعة');
                return;
            }
            res.writeHead(200, {
                'Content-Type': 'text/html; charset=utf-8',
                'Cache-Control': 'no-cache'
            });
            res.end(data);
        });
    } else if (pathname === '/terminal' || pathname === '/index.html' || pathname === '/phone' || pathname === '/call') {
        fs.readFile(HTML_FILE, (err, data) => {
            if (err) {
                res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
                res.end('خطأ في تحميل صفحة الهاتف الطرفي');
                return;
            }
            res.writeHead(200, {
                'Content-Type': 'text/html; charset=utf-8',
                'Cache-Control': 'no-cache'
            });
            res.end(data);
        });
    } else if (pathname === '/api/telemetry' || pathname === '/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(getTelemetryData()));
    } else if (pathname === '/api/sms') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            total: smsHistory.length,
            messages: smsHistory
        }));
    } else if (pathname === '/api/tunnel') {
        const tUrl = getLiveTunnelUrl();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: tUrl ? 'active' : 'offline',
            url: tUrl,
            time: getTimestamp()
        }));
    } else if (pathname === '/tunnel' || pathname === '/online') {
        const tUrl = getLiveTunnelUrl();
        if (tUrl) {
            res.writeHead(302, { 'Location': tUrl });
            res.end();
        } else {
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end('<h3>جاري تشغيل النفق السحابي... يرجى تحديث الصفحة بعد لحظات.</h3>');
        }
    } else if (pathname === '/manifest.json') {
        const manifestPath = path.join(__dirname, 'manifest.json');
        fs.readFile(manifestPath, (err, data) => {
            if (err) {
                res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
                res.end('Not found');
                return;
            }
            res.writeHead(200, {
                'Content-Type': 'application/manifest+json; charset=utf-8',
                'Cache-Control': 'public, max-age=3600'
            });
            res.end(data);
        });
    } else if (pathname === '/sw.js') {
        const swPath = path.join(__dirname, 'sw.js');
        fs.readFile(swPath, (err, data) => {
            if (err) {
                res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
                res.end('Not found');
                return;
            }
            res.writeHead(200, {
                'Content-Type': 'application/javascript; charset=utf-8',
                'Cache-Control': 'no-cache'
            });
            res.end(data);
        });
    } else if (pathname === '/icon-192.png' || pathname === '/icon-512.png') {
        const iconPath = path.join(__dirname, pathname.substring(1));
        if (fs.existsSync(iconPath)) {
            res.writeHead(200, {
                'Content-Type': 'image/png',
                'Cache-Control': 'public, max-age=86400'
            });
            fs.createReadStream(iconPath).pipe(res);
        } else {
            res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('Icon not found');
        }
    } else if (pathname === '/download' || pathname === '/install' || pathname === '/app') {
        if (fs.existsSync(DOWNLOAD_FILE)) {
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            fs.createReadStream(DOWNLOAD_FILE).pipe(res);
        } else if (fs.existsSync(APK_FILE)) {
            const stat = fs.statSync(APK_FILE);
            res.writeHead(200, {
                'Content-Type': 'application/vnd.android.package-archive',
                'Content-Length': stat.size,
                'Content-Disposition': `attachment; filename="${APK_FILENAME}"`
            });
            fs.createReadStream(APK_FILE).pipe(res);
        }
    } else if (pathname === '/download/apk' || pathname === '/app.apk' || pathname === '/GSM-Gateway.apk' || pathname === '/api/download-apk' || pathname === '/' + APK_FILENAME || pathname === '/app-release.apk') {
        if (fs.existsSync(APK_FILE)) {
            const stat = fs.statSync(APK_FILE);
            res.writeHead(200, {
                'Content-Type': 'application/vnd.android.package-archive',
                'Content-Length': stat.size,
                'Content-Disposition': `attachment; filename="${APK_FILENAME}"`
            });
            if (req.method === 'HEAD') {
                res.end();
            } else {
                fs.createReadStream(APK_FILE).pipe(res);
            }
        } else {
            res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('ملف التطبيق غير موجود على السيرفر');
        }
    } else if (pathname === '/download/zip' || pathname === '/app.zip' || pathname === '/GSM-Gateway.zip' || pathname === '/' + ZIP_FILENAME) {
        if (ZIP_FILE && fs.existsSync(ZIP_FILE)) {
            const stat = fs.statSync(ZIP_FILE);
            res.writeHead(200, {
                'Content-Type': 'application/zip',
                'Content-Length': stat.size,
                'Content-Disposition': `attachment; filename="${ZIP_FILENAME}"`
            });
            if (req.method === 'HEAD') {
                res.end();
            } else {
                fs.createReadStream(ZIP_FILE).pipe(res);
            }
        } else {
            res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('الملف المضغوط غير متوفر حالياً');
        }
    } else {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('غير موجود');
    }
});

// WebSocket Server
const wss = new WebSocketServer({ server });

function getClientRoom(ws) {
    return ws.currentRoom || 'GSM-1001';
}

function allocateExtension(roomId, requestedExt = null, callingWs = null) {
    const roomPeers = rooms.get(roomId);
    const used = new Set();
    if (roomPeers) {
        for (const client of roomPeers) {
            if (client !== callingWs && client.extension) {
                used.add(client.extension);
            }
        }
    }
    if (requestedExt) {
        let norm = requestedExt.toString().trim();
        if (!norm.startsWith('#')) norm = '#' + norm;
        if (!used.has(norm)) return norm;
    }
    for (let i = 101; i <= 199; i++) {
        const ext = `#${i}`;
        if (!used.has(ext)) return ext;
    }
    return `#${Math.floor(200 + Math.random() * 800)}`;
}

function broadcastPeerLists(roomId) {
    const roomPeers = rooms.get(roomId);
    if (!roomPeers) return;

    const gateways = [];
    const terminals = [];

    for (const client of roomPeers) {
        if (client.clientRole === 'GATEWAY' && client.readyState === WebSocket.OPEN) {
            gateways.push({
                id: client.gatewayId,
                name: client.gatewayName || 'هاتف وسيط GSM',
                carrier: client.carrier || 'GSM',
                isBusy: client.isBusy || false
            });
        } else if (client.clientRole === 'TERMINAL' && client.readyState === WebSocket.OPEN) {
            terminals.push({
                id: client.terminalId || client.extension,
                extension: client.extension || '#101',
                name: client.terminalName || `هاتف طرفي (${client.extension})`,
                isBusy: client.isBusy || false,
                currentCallNumber: client.currentCallNumber || ''
            });
        }
    }

    const payloadGateways = JSON.stringify({
        type: 'GATEWAY_LIST',
        room: roomId,
        gateways: gateways,
        timestamp: Date.now()
    });

    const payloadTerminals = JSON.stringify({
        type: 'TERMINAL_LIST',
        room: roomId,
        terminals: terminals,
        timestamp: Date.now()
    });

    const payloadPeers = JSON.stringify({
        type: 'PEER_LISTS',
        room: roomId,
        gateways: gateways,
        terminals: terminals,
        timestamp: Date.now()
    });

    for (const client of roomPeers) {
        if (client.readyState === WebSocket.OPEN) {
            try {
                client.send(payloadGateways, { binary: false });
                client.send(payloadTerminals, { binary: false });
                client.send(payloadPeers, { binary: false });
            } catch (ignored) {}
        }
    }

    notifyTelemetryUpdate();
}

function broadcastGatewayList(roomId) {
    broadcastPeerLists(roomId);
}

function joinRoom(ws, roomId) {
    if (ws.currentRoom && rooms.has(ws.currentRoom)) {
        rooms.get(ws.currentRoom).delete(ws);
        broadcastGatewayList(ws.currentRoom);
        if (rooms.get(ws.currentRoom).size === 0) {
            rooms.delete(ws.currentRoom);
        }
    }

    ws.currentRoom = roomId || 'GSM-1001';
    if (!rooms.has(ws.currentRoom)) {
        rooms.set(ws.currentRoom, new Set());
    }
    rooms.get(ws.currentRoom).add(ws);
}

// 15s keepalive ping interval
setInterval(() => {
    for (const [roomId, roomClients] of rooms.entries()) {
        for (const client of roomClients) {
            if (client.readyState === WebSocket.OPEN) {
                try {
                    client.ping();
                } catch (ignored) {}
            }
        }
    }
    for (const monitor of monitors) {
        if (monitor.readyState === WebSocket.OPEN) {
            try {
                monitor.ping();
            } catch (ignored) {}
        }
    }
}, 15000);

wss.on('connection', (ws, req) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const queryRoom = url.searchParams.get('room') || 'GSM-1001';
    joinRoom(ws, queryRoom);

    ws.on('message', (data, isBinary) => {
        const currentRoom = getClientRoom(ws);
        const roomPeers = rooms.get(currentRoom);

        if (isBinary) {
            totalAudioPackets++;
            // STRICT CALL ROUTING: Only route audio between paired peers during an active call!
            if (ws.pairedPeer && ws.pairedPeer.readyState === WebSocket.OPEN && ws.isBusy) {
                ws.pairedPeer.send(data, { binary: true });
            }
            // NEVER broadcast raw audio to the room when no call is active!
            return;
        }

        try {
            const text = data.toString();
            const json = JSON.parse(text);

            if (json.type === 'JOIN') {
                if (json.role === 'MONITOR') {
                    ws.isMonitor = true;
                    monitors.add(ws);
                    ws.send(JSON.stringify({
                        type: 'TELEMETRY_UPDATE',
                        data: getTelemetryData()
                    }));
                    return;
                }

                if (json.room && json.room !== ws.currentRoom) {
                    joinRoom(ws, json.room);
                }
                const activeRoom = getClientRoom(ws);
                ws.clientRole = json.role || 'TERMINAL';
                if (json.role === 'GATEWAY') {
                    ws.gatewayId = json.gatewayId || ('GW-' + Math.floor(1000 + Math.random() * 9000));
                    ws.gatewayName = json.gatewayName || json.deviceName || 'هاتف وسيط GSM';
                    ws.carrier = json.carrier || 'GSM';
                    ws.isBusy = false;
                    addAuditLog(`تم تسجيل هاتف وسيط (${ws.gatewayName}) بشبكة (${ws.carrier}) في الغرفة [${activeRoom}]`, 'success');
                } else {
                    ws.extension = allocateExtension(activeRoom, json.extension, ws);
                    ws.terminalId = json.terminalId || ('TERM-' + ws.extension.replace('#', ''));
                    ws.terminalName = json.terminalName || `هاتف طرفي (${ws.extension})`;
                    ws.isBusy = false;

                    const assignedPayload = JSON.stringify({
                        type: 'TERMINAL_ASSIGNED',
                        extension: ws.extension,
                        terminalId: ws.terminalId,
                        terminalName: ws.terminalName,
                        room: activeRoom
                    });
                    ws.send(assignedPayload);
                    ws.send(JSON.stringify({
                        type: 'EXTENSION_ASSIGNED',
                        extension: ws.extension,
                        terminalId: ws.terminalId,
                        terminalName: ws.terminalName,
                        room: activeRoom
                    }));

                    addAuditLog(`انضم هاتف طرفي بالتحويلة [${ws.extension}] للغرفة [${activeRoom}]`, 'info');
                }
                broadcastPeerLists(activeRoom);
                notifyTelemetryUpdate();
                return;
            }

            if (json.type === 'UPDATE_EXTENSION') {
                const activeRoom = getClientRoom(ws);
                const oldExt = ws.extension || '#101';
                ws.extension = allocateExtension(activeRoom, json.extension, ws);
                ws.terminalId = 'TERM-' + ws.extension.replace('#', '');
                ws.terminalName = `هاتف طرفي (${ws.extension})`;

                const updatedPayload = JSON.stringify({
                    type: 'TERMINAL_ASSIGNED',
                    extension: ws.extension,
                    terminalId: ws.terminalId,
                    terminalName: ws.terminalName,
                    room: activeRoom
                });
                ws.send(updatedPayload);
                ws.send(JSON.stringify({
                    type: 'EXTENSION_ASSIGNED',
                    extension: ws.extension,
                    terminalId: ws.terminalId,
                    terminalName: ws.terminalName,
                    room: activeRoom
                }));

                addAuditLog(`تم تعديل تحويلة الهاتف الطرفي من (${oldExt}) إلى (${ws.extension})`, 'info');
                broadcastPeerLists(activeRoom);
                notifyTelemetryUpdate();
                return;
            }

            if (json.type === 'GATEWAY_INFO') {
                ws.clientRole = 'GATEWAY';
                if (json.carrier) ws.carrier = json.carrier;
                if (json.battery !== undefined) ws.battery = json.battery;
                if (json.gatewayId) ws.gatewayId = json.gatewayId;
                if (json.gatewayName) ws.gatewayName = json.gatewayName;
                if (json.callState) {
                    ws.isBusy = (json.callState === 'CONNECTED' || json.callState === 'DIALING' || json.callState === 'RINGING');
                }
                notifyTelemetryUpdate();
                broadcastPeerLists(getClientRoom(ws));
                return;
            }

            if (json.type === 'GET_GATEWAYS' || json.type === 'GET_PEERS') {
                broadcastPeerLists(currentRoom);
                notifyTelemetryUpdate();
                return;
            }

            if (json.type === 'DIAL_REQUEST') {
                totalCalls++;
                const targetId = json.targetGatewayId;
                const phoneNum = (json.phoneNumber || '').trim();

                // Check for Intercom Terminal-to-Terminal Call (e.g. #102 or 102)
                const isIntercom = phoneNum.startsWith('#') || (/^\d{3}$/.test(phoneNum) && !phoneNum.startsWith('9') && !phoneNum.startsWith('0'));
                const targetExt = phoneNum.startsWith('#') ? phoneNum : ('#' + phoneNum);

                if (isIntercom) {
                    addAuditLog(`طلب مكالمة داخلية (Intercom) إلى التحويلة (${targetExt}) من التحويلة (${ws.extension || '#101'})`, 'call');

                    if (targetExt === ws.extension) {
                        ws.send(JSON.stringify({
                            type: 'CALL_STATE',
                            callState: 'BUSY',
                            phoneNumber: targetExt,
                            message: 'لا يمكنك الاتصال برقم تحويلتك الخاصة!'
                        }));
                        return;
                    }

                    let targetTerminal = null;
                    const activeRoom = getClientRoom(ws);
                    const currentPeers = rooms.get(activeRoom);
                    if (currentPeers) {
                        for (const client of currentPeers) {
                            if (client.clientRole === 'TERMINAL' && client !== ws && client.readyState === WebSocket.OPEN) {
                                if (client.extension === targetExt) {
                                    targetTerminal = client;
                                    break;
                                }
                            }
                        }
                    }

                    if (targetTerminal) {
                        if (targetTerminal.isBusy) {
                            ws.send(JSON.stringify({
                                type: 'CALL_STATE',
                                callState: 'BUSY',
                                phoneNumber: targetExt,
                                message: `التحويلة (${targetExt}) مشغولة بمكالمة أخرى حالياً!`
                            }));
                            addAuditLog(`التحويلة (${targetExt}) مشغولة حالياً`, 'warn');
                            return;
                        }

                        // Pair terminals together for bi-directional audio
                        ws.pairedPeer = targetTerminal;
                        targetTerminal.pairedPeer = ws;
                        ws.isBusy = true;
                        targetTerminal.isBusy = true;
                        ws.callType = 'INTERCOM';
                        targetTerminal.callType = 'INTERCOM';
                        ws.currentCallNumber = targetExt;
                        targetTerminal.currentCallNumber = ws.extension || '#101';
                        ws.callStartTime = Date.now();
                        targetTerminal.callStartTime = Date.now();

                        // Ringing notification to caller
                        ws.send(JSON.stringify({
                            type: 'CALL_STATE',
                            callState: 'RINGING',
                            phoneNumber: targetExt,
                            isIntercom: true
                        }));

                        // Incoming call modal to target
                        targetTerminal.send(JSON.stringify({
                            type: 'INCOMING_CALL',
                            phoneNumber: ws.extension || '#101',
                            callerName: ws.terminalName || `هاتف طرفي (${ws.extension})`,
                            isIntercom: true,
                            timestamp: Date.now()
                        }));

                        addAuditLog(`رنين المكالمة الداخلية: التحويلة (${targetExt}) ترن الآن من التحويلة (${ws.extension})`, 'call');
                        broadcastPeerLists(activeRoom);
                        return;
                    } else {
                        ws.send(JSON.stringify({
                            type: 'CALL_STATE',
                            callState: 'NO_ANSWER',
                            phoneNumber: targetExt,
                            message: `التحويلة (${targetExt}) غير متصلة بالشبكة حالياً`
                        }));
                        addAuditLog(`فشل الاتصال: التحويلة (${targetExt}) غير موجودة أو غير متصلة`, 'warn');
                        return;
                    }
                }

                // Normal Cellular Call via GSM Gateway Phone
                addAuditLog(`طلب مكالمة خلوية جديد لرقم (${phoneNum}) موجه للوسيط [${targetId || 'أي وسيط متاح'}]`, 'call');

                let targetFound = false;
                const activeRoom = getClientRoom(ws);
                const currentPeers = rooms.get(activeRoom);
                if (currentPeers) {
                    for (const client of currentPeers) {
                        if (client.clientRole === 'GATEWAY' && client.readyState === WebSocket.OPEN) {
                            if (!targetId || client.gatewayId === targetId) {
                                client.send(data, { binary: false });
                                ws.pairedPeer = client;
                                client.pairedPeer = ws;
                                ws.isBusy = true;
                                client.isBusy = true;
                                ws.callType = 'GSM';
                                client.callType = 'GSM';
                                client.currentCallNumber = phoneNum;
                                ws.currentCallNumber = phoneNum;
                                client.callStartTime = Date.now();
                                ws.callStartTime = Date.now();
                                targetFound = true;
                                addAuditLog(`تم اقتران المكالمة مع الوسيط (${client.gatewayName}) بنجاح`, 'call');
                                break;
                            }
                        }
                    }
                }
                if (targetFound) {
                    broadcastPeerLists(activeRoom);
                    return;
                } else {
                    addAuditLog(`فشل العثور على وسيط متاح لتمرير المكالمة إلى (${phoneNum})`, 'warn');
                    try {
                        ws.send(JSON.stringify({
                            type: 'CALL_STATE',
                            callState: 'ENDED',
                            phoneNumber: phoneNum,
                            message: 'تعذر الاتصال: لا يوجد هاتف وسيط (Gateway) متاح ومتصل حالياً في هذه الغرفة!'
                        }));
                    } catch (e) {}
                    return;
                }
            }

            if (json.type === 'ANSWER_CALL') {
                addAuditLog(`قبول المكالمة من ${ws.clientRole} (${ws.extension || ws.gatewayName || ''})`, 'call');
                ws.isBusy = true;
                if (!ws.callStartTime) ws.callStartTime = Date.now();

                if (ws.pairedPeer && ws.pairedPeer.readyState === WebSocket.OPEN) {
                    ws.pairedPeer.isBusy = true;
                    if (!ws.pairedPeer.callStartTime) ws.pairedPeer.callStartTime = Date.now();

                    if (ws.callType === 'INTERCOM' || ws.pairedPeer.callType === 'INTERCOM') {
                        ws.send(JSON.stringify({
                            type: 'CALL_STATE',
                            callState: 'CONNECTED',
                            phoneNumber: ws.pairedPeer.extension,
                            isIntercom: true
                        }));
                        ws.pairedPeer.send(JSON.stringify({
                            type: 'CALL_STATE',
                            callState: 'CONNECTED',
                            phoneNumber: ws.extension,
                            isIntercom: true
                        }));
                        addAuditLog(`المكالمة الداخلية متصلة الآن بين (${ws.extension}) و (${ws.pairedPeer.extension}) - جاري بث الصوت المباشر`, 'call');
                        broadcastPeerLists(currentRoom);
                        return;
                    } else {
                        // Forward to gateway
                        ws.pairedPeer.send(data, { binary: false });
                    }
                }
            }

            if (json.type === 'CALL_STATE') {
                if (json.callState === 'CONNECTED') {
                    ws.isBusy = true;
                    if (json.phoneNumber) ws.currentCallNumber = json.phoneNumber;
                    if (!ws.callStartTime) ws.callStartTime = Date.now();
                    addAuditLog(`المكالمة متصلة الآن - جاري بث الصوت ثنائي الاتجاه`, 'call');
                } else if (json.callState === 'RINGING') {
                    if (json.phoneNumber) ws.currentCallNumber = json.phoneNumber;
                    addAuditLog(`رنين وارد / جاري الاتصال بالرقم...`, 'warn');
                } else if (json.callState === 'ENDED' || json.callState === 'BUSY' || json.callState === 'NO_ANSWER' || json.callState === 'REJECTED') {
                    addAuditLog(`انتهت المكالمة (${json.callState})`, 'info');
                    if (ws.pairedPeer) {
                        try {
                            ws.pairedPeer.send(JSON.stringify({
                                type: 'CALL_STATE',
                                callState: json.callState,
                                phoneNumber: ws.currentCallNumber || '',
                                timestamp: Date.now()
                            }));
                        } catch (ignored) {}
                        ws.pairedPeer.pairedPeer = null;
                        ws.pairedPeer.isBusy = false;
                        ws.pairedPeer.callStartTime = null;
                        ws.pairedPeer.currentCallNumber = '';
                        ws.pairedPeer.callType = null;
                        ws.pairedPeer = null;
                    }
                    ws.isBusy = false;
                    ws.callStartTime = null;
                    ws.currentCallNumber = '';
                    ws.callType = null;
                    broadcastPeerLists(currentRoom);
                }
            }

            if (json.type === 'HANGUP_CALL' || json.type === 'EMERGENCY_HANGUP' || json.type === 'REJECT_CALL') {
                addAuditLog(`طلب إنهاء المكالمة من ${ws.clientRole || 'الطرفي'} (${ws.extension || ''})`, 'warn');
                if (ws.pairedPeer) {
                    try {
                        ws.pairedPeer.send(JSON.stringify({
                            type: 'CALL_STATE',
                            callState: (json.type === 'REJECT_CALL') ? 'REJECTED' : 'ENDED',
                            phoneNumber: ws.extension || '',
                            room: currentRoom,
                            role: 'CONTROLLER',
                            timestamp: Date.now()
                        }));
                    } catch (ignored) {}
                    ws.pairedPeer.pairedPeer = null;
                    ws.pairedPeer.isBusy = false;
                    ws.pairedPeer.callStartTime = null;
                    ws.pairedPeer.currentCallNumber = '';
                    ws.pairedPeer.callType = null;
                    ws.pairedPeer = null;
                }
                ws.isBusy = false;
                ws.callStartTime = null;
                ws.currentCallNumber = '';
                ws.callType = null;
                broadcastPeerLists(currentRoom);
            }

            if (json.type === 'SMS_SEND_REQUEST') {
                const targetId = json.targetGatewayId;
                const recipient = json.phoneNumber || json.smsRecipient || json.recipient || '';
                const body = json.body || json.smsBody || '';
                const smsId = json.smsId || ('sms_' + Date.now() + '_' + Math.random().toString(36).substr(2, 5));

                const targetGw = findGateway(targetId);
                const smsRecord = {
                    id: smsId,
                    direction: 'OUTGOING',
                    gatewayId: targetGw ? targetGw.gatewayId : (targetId || 'UNKNOWN'),
                    gatewayName: targetGw ? targetGw.gatewayName : 'هاتف وسيط GSM',
                    recipient: recipient,
                    sender: 'Terminal / Dashboard',
                    body: body,
                    status: targetGw ? 'PENDING' : 'FAILED',
                    error: targetGw ? null : 'لا يوجد هاتف وسيط متصل حالياً لإرسال الرسالة',
                    timestamp: Date.now()
                };

                smsHistory.unshift(smsRecord);
                if (smsHistory.length > 100) smsHistory.pop();

                if (targetGw) {
                    addAuditLog(`طلب إرسال SMS لرقم (${recipient}) عبر الوسيط (${targetGw.gatewayName})`, 'info');
                    targetGw.send(JSON.stringify({
                        type: 'SMS_SEND_REQUEST',
                        targetGatewayId: targetGw.gatewayId,
                        phoneNumber: recipient,
                        smsRecipient: recipient,
                        body: body,
                        smsBody: body,
                        smsId: smsId,
                        timestamp: Date.now()
                    }));
                } else {
                    addAuditLog(`فشل إرسال SMS: لا يوجد هاتف وسيط متاح لإرسال الرسالة إلى (${recipient})`, 'warn');
                }

                broadcastToMonitors({
                    type: 'SMS_UPDATE',
                    sms: smsRecord
                });
                return;
            }

            if (json.type === 'SMS_SEND_STATUS') {
                const smsId = json.smsId;
                const success = json.smsSuccess || json.success || false;
                const error = json.smsError || json.error || null;
                const recipient = json.smsRecipient || json.recipient || '';

                const existing = smsHistory.find(s => s.id === smsId);
                if (existing) {
                    existing.status = success ? 'SENT' : 'FAILED';
                    existing.error = error;
                    existing.sentTimestamp = Date.now();
                }

                addAuditLog(success ? `✓ تم إرسال رسالة SMS بنجاح إلى (${recipient}) عبر الوسيط (${ws.gatewayName || ws.gatewayId})` : `❌ فشل إرسال SMS إلى (${recipient}): ${error}`, success ? 'success' : 'warn');

                broadcastToMonitors({
                    type: 'SMS_STATUS_UPDATE',
                    smsId: smsId,
                    status: success ? 'SENT' : 'FAILED',
                    error: error,
                    recipient: recipient,
                    timestamp: Date.now()
                });
                return;
            }

            if (json.type === 'SMS_INCOMING') {
                const sender = json.smsSender || json.sender || json.phoneNumber || 'غير معروف';
                const body = json.smsBody || json.body || '';
                const incomingSms = {
                    id: json.smsId || ('sms_' + Date.now() + '_' + Math.random().toString(36).substr(2, 5)),
                    direction: 'INCOMING',
                    gatewayId: ws.gatewayId || 'GW-UNKNOWN',
                    gatewayName: ws.gatewayName || 'هاتف وسيط GSM',
                    sender: sender,
                    recipient: 'شريحة الوسيط',
                    body: body,
                    status: 'RECEIVED',
                    timestamp: json.timestamp || Date.now()
                };

                smsHistory.unshift(incomingSms);
                if (smsHistory.length > 100) smsHistory.pop();

                addAuditLog(`📩 رسالة SMS واردة من (${sender}) على الوسيط (${incomingSms.gatewayName}): ${body.substring(0, 40)}...`, 'info');

                broadcastToMonitors({
                    type: 'SMS_INCOMING',
                    sms: incomingSms
                });
                return;
            }

        } catch (ignored) {}

        // Forward signaling
        if (ws.pairedPeer && ws.pairedPeer.readyState === WebSocket.OPEN) {
            ws.pairedPeer.send(data, { binary: false });
        } else if (roomPeers) {
            for (const client of roomPeers) {
                if (client !== ws && client.readyState === WebSocket.OPEN) {
                    client.send(data, { binary: false });
                }
            }
        }
    });

    ws.on('close', () => {
        if (ws.isMonitor) {
            monitors.delete(ws);
            return;
        }
        if (ws.pairedPeer) {
            if (ws.pairedPeer.readyState === WebSocket.OPEN) {
                try {
                    ws.pairedPeer.send(JSON.stringify({
                        type: 'CALL_STATE',
                        callState: 'ENDED',
                        message: 'انقطع اتصال الطرف الآخر',
                        timestamp: Date.now()
                    }));
                } catch (ignored) {}
            }
            ws.pairedPeer.pairedPeer = null;
            ws.pairedPeer.isBusy = false;
            ws.pairedPeer.callStartTime = null;
            ws.pairedPeer.currentCallNumber = '';
            ws.pairedPeer.callType = null;
            ws.pairedPeer = null;
        }
        if (ws.currentRoom && rooms.has(ws.currentRoom)) {
            rooms.get(ws.currentRoom).delete(ws);
            if (ws.clientRole === 'GATEWAY') {
                addAuditLog(`انقطع اتصال هاتف الوسيط (${ws.gatewayName || ws.gatewayId})`, 'warn');
            } else if (ws.clientRole === 'TERMINAL') {
                addAuditLog(`غادر الهاتف الطرفي (${ws.extension || 'بدون تحويلة'}) الغرفة`, 'info');
            }
            broadcastPeerLists(ws.currentRoom);
            notifyTelemetryUpdate();
            if (rooms.get(ws.currentRoom).size === 0) {
                rooms.delete(ws.currentRoom);
            }
        }
        notifyTelemetryUpdate();
    });

    ws.on('error', (err) => {
        console.error('[WebSocket Error]', err.message);
    });
});

server.listen(PORT, '0.0.0.0', () => {
    const localIps = getLocalIps();
    const primaryIp = localIps.length > 0 ? localIps[0].ip : 'localhost';

    console.log(`================================================================`);
    console.log(`  🌐 GSM Cloud Relay Server & Web Terminal Started!`);
    console.log(`================================================================`);
    console.log(`  💻 Web Terminal (on PC):   http://localhost:${PORT}`);
    console.log(`  📊 Live Dashboard:         http://localhost:${PORT}/dashboard`);
    console.log(`  📱 Web Terminal (on LAN):  http://${primaryIp}:${PORT}`);
    console.log(`  📡 WebSocket Broker URL:   ws://${primaryIp}:${PORT}`);
    console.log(`================================================================`);
});

// =========================================================================
// 24/7 RENDER KEEP-ALIVE AUTO PING (Prevents Free-tier Sleep)
// =========================================================================
const RENDER_EXTERNAL_URL = process.env.RENDER_EXTERNAL_URL || 'https://relay1-tdgn.onrender.com';
setInterval(() => {
    try {
        const pingUrl = `${RENDER_EXTERNAL_URL}/api/telemetry`;
        const client = pingUrl.startsWith('https') ? require('https') : require('http');
        client.get(pingUrl, (res) => {
            // Keep alive heartbeat ok
        }).on('error', () => {});
    } catch (ignored) {}
}, 9 * 60 * 1000); // Ping every 9 minutes so Render never sleeps!
