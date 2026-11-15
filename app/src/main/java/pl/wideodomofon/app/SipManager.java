package pl.wideodomofon.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.linphone.core.Account;
import org.linphone.core.AccountParams;
import org.linphone.core.Address;
import org.linphone.core.AudioDevice;
import org.linphone.core.AuthInfo;
import org.linphone.core.Call;
import org.linphone.core.CallParams;
import org.linphone.core.CallStats;
import org.linphone.core.Core;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.Factory;
import org.linphone.core.MediaDirection;
import org.linphone.core.Reason;
import org.linphone.core.RegistrationState;
import org.linphone.core.Transports;
import org.linphone.core.VideoActivationPolicy;

/**
 * Obsluga SIP (Linphone SDK). Wszystkie metody wolac wylacznie z watku glownego.
 * Przyjmuje polaczenia z serwera SIP oraz bezposrednie po adresie IP tabletu.
 */
public class SipManager {
    public interface Events {
        void onIncoming(String who);

        void onConnected();

        void onEnded(String reason);
    }

    private final Context ctx;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Core core;
    private Call call;
    private boolean connected;
    private boolean accountConfigured;
    private boolean regOk;
    private long lastOk;
    private long lastStartTry;
    private Object videoWindow;
    public volatile String regState = "-";
    private int statsCount;

    private long streamsSince;

    private void requestKeyFrame() {
        try {
            if (call != null && connected && Cfg.sipVideo()) call.sendVfuRequest();
        } catch (Throwable ignored) {
        }
    }

    private String videoSummary() {
        try {
            if (call == null) return "brak polaczenia";
            CallParams rp = call.getRemoteParams();
            CallParams cp = call.getCurrentParams();
            boolean offered = rp != null && rp.isVideoEnabled();
            boolean agreed = cp != null && cp.isVideoEnabled();
            String codec = "";
            try {
                org.linphone.core.PayloadType pt = cp == null ? null : cp.getUsedVideoPayloadType();
                if (pt != null) codec = " " + pt.getMimeType();
            } catch (Throwable ignored) {
            }
            CallStats vs = call.getVideoStats();
            float down = vs == null ? 0 : vs.getDownloadBandwidth();
            return String.format(java.util.Locale.US, "w polaczeniu od drugiej strony: %s, uzgodnione: %s%s, odbior %.0f kb/s",
                    offered ? "tak" : "NIE", agreed ? "tak" : "NIE", codec, down);
        } catch (Throwable t) {
            return "blad odczytu: " + t;
        }
    }

    /** Opis przyczyny braku obrazu SIP do pokazania na ekranie, albo null gdy obraz dociera / za wczesnie na ocene. */
    public String videoProblem() {
        try {
            if (call == null || !connected) return null;
            CallParams rp = call.getRemoteParams();
            CallParams cp = call.getCurrentParams();
            if (rp != null && !rp.isVideoEnabled()) {
                return "Połączenie przyszło bez obrazu – bramofon nie wysyła wideo w rozmowie SIP albo serwer SIP je usuwa.";
            }
            if (cp != null && !cp.isVideoEnabled()) {
                return "Obraz nie został uzgodniony – niezgodny kodek wideo (wymagany H.264).";
            }
            if (SystemClock.uptimeMillis() - streamsSince < 6000) return null;
            CallStats vs = call.getVideoStats();
            if (vs == null || vs.getDownloadBandwidth() < 1) {
                return "Obraz uzgodniony, ale dane wideo nie docierają – serwer SIP nie przekazuje wideo albo blokuje je sieć (NAT).";
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Pomiar dzwieku w trakcie rozmowy - pokazuje w dzienniku, czy pakiety ida w obie strony. */
    private final Runnable audioStats = new Runnable() {
        @Override
        public void run() {
            try {
                if (call == null || core == null || !connected) return;
                CallStats st = call.getAudioStats();
                boolean perm = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (st != null) {
                    float down = st.getDownloadBandwidth(), up = st.getUploadBandwidth();
                    L.i(String.format(java.util.Locale.US,
                            "Audio: odbior %.0f kb/s, wysylanie %.0f kb/s, mikrofon %s, uprawnienie mikrofonu %s%s",
                            down, up, core.isMicEnabled() ? "wlaczony" : "wyciszony", perm ? "jest" : "BRAK",
                            down < 1 ? "  <- brak pakietow dzwieku z drugiej strony (zapora, NAT albo serwer SIP nie przekazuje dzwieku)" : ""));
                    if (Cfg.sipVideo()) {
                        String problem = videoProblem();
                        L.i("Wideo SIP: " + videoSummary() + (problem == null ? "" : "  <- " + problem));
                        requestKeyFrame();
                    }
                }
                if (++statsCount < 6) main.postDelayed(this, 4000);
            } catch (Throwable t) {
                L.e("SIP: pomiar dzwieku", t);
            }
        }
    };

    public SipManager(Context ctx, Events events) {
        this.ctx = ctx.getApplicationContext();
        this.events = events;
    }

    private final CoreListenerStub listener = new CoreListenerStub() {
        @Override
        public void onCallStateChanged(Core c, final Call cl, Call.State state, String message) {
            L.i("SIP: stan polaczenia " + state + (message != null ? " (" + message + ")" : ""));
            if (state == Call.State.IncomingReceived) {
                if (call != null && call != cl) {
                    L.i("SIP: drugie polaczenie odrzucone (zajete)");
                    try {
                        cl.decline(Reason.Busy);
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                call = cl;
                connected = false;
                String who = describe(cl);
                if (Cfg.sipVideo() && Cfg.b("early_media")) {
                    // Obraz jeszcze przed odebraniem (183 Session Progress).
                    main.post(() -> {
                        try {
                            if (call == cl && c.getCallsNb() > 0) {
                                CallParams p = c.createCallParams(cl);
                                if (p != null) {
                                    p.setVideoEnabled(true);
                                    p.setVideoDirection(MediaDirection.RecvOnly);
                                    cl.acceptEarlyMediaWithParams(p);
                                }
                            }
                        } catch (Throwable t) {
                            L.e("SIP: early media", t);
                        }
                    });
                }
                events.onIncoming(who);
            } else if (state == Call.State.StreamsRunning || state == Call.State.Connected) {
                if (cl == call && !connected && state == Call.State.StreamsRunning) {
                    connected = true;
                    routeToSpeaker();
                    statsCount = 0;
                    streamsSince = SystemClock.uptimeMillis();
                    // Bramofon wysyla klatke kluczowa rzadko - prosimy o nia od razu, inaczej obraz bywa czarny.
                    requestKeyFrame();
                    main.postDelayed(SipManager.this::requestKeyFrame, 1500);
                    main.removeCallbacks(audioStats);
                    main.postDelayed(audioStats, 3000);
                    events.onConnected();
                }
            } else if (state == Call.State.End || state == Call.State.Error
                    || state == Call.State.Released) {
                if (cl == call) {
                    call = null;
                    connected = false;
                    main.removeCallbacks(audioStats);
                    try {
                        c.setMicEnabled(true);
                    } catch (Throwable ignored) {
                    }
                    events.onEnded(message != null ? message : String.valueOf(state));
                }
            }
        }

        @Override
        public void onAccountRegistrationStateChanged(Core c, Account account,
                                                      RegistrationState state, String message) {
            regState = String.valueOf(state) + (message != null && !message.isEmpty() ? " (" + message + ")" : "");
            regOk = state == RegistrationState.Ok;
            if (regOk) lastOk = SystemClock.uptimeMillis();
            L.i("SIP: rejestracja " + regState);
        }
    };

    private static String describe(Call cl) {
        try {
            Address a = cl.getRemoteAddress();
            String name = a.getDisplayName();
            String user = a.getUsername();
            String host = a.getDomain();
            if (name != null && !name.isEmpty()) return name;
            if (user != null && !user.isEmpty()) return user + "@" + host;
            return String.valueOf(host);
        } catch (Throwable t) {
            return "Bramofon";
        }
    }

    public void start() {
        lastStartTry = SystemClock.uptimeMillis();
        lastOk = lastStartTry;
        try {
            Factory f = Factory.instance();
            Core c = f.createCore(null, null, ctx);
            c.setUserAgent("Wideodomofon", BuildConfig.VERSION_NAME);
            c.setAutoIterateEnabled(true);
            c.setIpv6Enabled(false);
            c.setKeepAliveEnabled(true);

            int port = Cfg.i("sip_port");
            if (port <= 0 || port > 65535) port = 5060;
            Transports tr = f.createTransports();
            tr.setUdpPort(port);
            tr.setTcpPort(port);
            tr.setTlsPort(0);
            tr.setDtlsPort(0);
            c.setTransports(tr);

            // Obraz tylko odbieramy - kamera tabletu nie jest uzywana.
            c.setVideoDisplayEnabled(true);
            c.setVideoCaptureEnabled(false);
            VideoActivationPolicy pol = f.createVideoActivationPolicy();
            pol.setAutomaticallyAccept(true);
            pol.setAutomaticallyInitiate(false);
            c.setVideoActivationPolicy(pol);
            try {
                StringBuilder codecs = new StringBuilder();
                boolean h264 = false;
                for (org.linphone.core.PayloadType pt : c.getVideoPayloadTypes()) {
                    pt.enable(true);
                    codecs.append(pt.getMimeType()).append(' ');
                    if ("H264".equalsIgnoreCase(pt.getMimeType())) h264 = true;
                }
                L.i("SIP: kodeki wideo: " + codecs.toString().trim()
                        + (h264 ? "" : "  <- UWAGA: brak H.264 na tym urzadzeniu, obraz z Akuvoxa przez SIP nie zadziala"));
            } catch (Throwable t) {
                L.e("SIP: kodeki wideo", t);
            }

            c.setEchoCancellationEnabled(Cfg.b("echo_cancel"));
            c.setMicGainDb(Cfg.i("mic_gain_db"));
            c.setPlaybackGainDb(Cfg.i("speaker_gain_db"));
            c.setIncTimeout(Math.max(10, Cfg.i("ring_timeout_s")));

            if (Cfg.b("ring_enabled")) {
                c.setNativeRingingEnabled(true);
                c.setRingDuringIncomingEarlyMedia(true);
            } else {
                c.setNativeRingingEnabled(false);
                c.setRing(null);
            }

            String dm = Cfg.s("dtmf_method");
            c.setUseRfc2833ForDtmf(true);
            c.setUseInfoForDtmf(false); // INFO wysylamy sami w sendDtmf()

            // Przechodzenie przez NAT (siec komorkowa, obce Wi-Fi): STUN i opcjonalnie ICE.
            org.linphone.core.NatPolicy nat = null;
            String stun = Cfg.s("stun_server");
            if (!stun.isEmpty() || Cfg.b("ice_enabled")) {
                try {
                    nat = c.createNatPolicy();
                    if (!stun.isEmpty()) {
                        nat.setStunServer(stun);
                        nat.setStunEnabled(true);
                    }
                    nat.setIceEnabled(Cfg.b("ice_enabled"));
                    c.setNatPolicy(nat);
                    L.i("SIP: NAT - STUN " + (stun.isEmpty() ? "brak" : stun) + ", ICE " + (Cfg.b("ice_enabled") ? "tak" : "nie"));
                } catch (Throwable t) {
                    nat = null;
                    L.e("SIP: ustawienia NAT", t);
                }
            }

            accountConfigured = false;
            regOk = false;
            regState = "bez rejestracji (tylko połączenia po IP)";
            String server = Cfg.s("sip_server");
            String user = Cfg.s("sip_user");
            if (Cfg.b("sip_enabled") && !server.isEmpty() && !user.isEmpty()) {
                String domain = server.contains(":") ? server.substring(0, server.indexOf(':')) : server;
                String transport = "tcp".equals(Cfg.s("sip_transport")) ? "tcp" : "udp";
                AuthInfo ai = f.createAuthInfo(user, null, Cfg.s("sip_pass"), null, null, domain);
                c.addAuthInfo(ai);
                AccountParams ap = c.createAccountParams();
                Address identity = f.createAddress("sip:" + user + "@" + domain);
                Address srv = f.createAddress("sip:" + server + ";transport=" + transport);
                if (identity == null || srv == null) {
                    L.i("SIP: bledny adres serwera lub nazwa uzytkownika");
                } else {
                    ap.setIdentityAddress(identity);
                    ap.setServerAddress(srv);
                    ap.setRegisterEnabled(true);
                    ap.setExpires(Math.max(60, Cfg.i("sip_expires")));
                    if (nat != null) {
                        try {
                            ap.setNatPolicy(nat);
                        } catch (Throwable ignored) {
                        }
                    }
                    Account acc = c.createAccount(ap);
                    c.addAccount(acc);
                    c.setDefaultAccount(acc);
                    accountConfigured = true;
                    regState = "łączenie…";
                }
            }

            c.addListener(listener);
            c.start();
            core = c;
            if (videoWindow != null) c.setNativeVideoWindowId(videoWindow);
            L.i("SIP: uruchomiono, port " + port + (accountConfigured ? ", konto " + user + "@" + server : ", bez rejestracji"));
        } catch (Throwable t) {
            core = null;
            regState = "blad uruchomienia: " + t;
            L.e("SIP: nie udalo sie uruchomic", t);
        }
    }

    public void stop() {
        Core c = core;
        core = null;
        call = null;
        connected = false;
        if (c == null) return;
        try {
            c.removeListener(listener);
            c.terminateAllCalls();
            c.setNativeVideoWindowId(null);
            c.stop();
        } catch (Throwable t) {
            L.e("SIP: zatrzymanie", t);
        }
    }

    public void restart() {
        L.i("SIP: restart");
        stop();
        start();
    }

    /** Wolane cyklicznie przez usluge - naprawia zawieszona rejestracje lub nieudany start. */
    public void healthCheck() {
        long now = SystemClock.uptimeMillis();
        if (core == null) {
            if (now - lastStartTry > 30000) start();
            return;
        }
        if (accountConfigured && !regOk && call == null && now - lastOk > 180000) {
            L.i("SIP: brak rejestracji od 3 minut - restart stosu SIP");
            restart();
        }
    }

    public boolean hasCall() {
        return call != null;
    }

    public boolean isRunning() {
        return core != null;
    }

    public boolean answer(boolean muted) {
        if (core == null || call == null) return false;
        try {
            boolean sipVideo = Cfg.sipVideo();
            core.setMicEnabled(!muted);
            CallParams p = core.createCallParams(call);
            if (p == null) {
                call.accept();
                return true;
            }
            p.setVideoEnabled(sipVideo);
            if (sipVideo) p.setVideoDirection(MediaDirection.RecvOnly);
            call.acceptWithParams(p);
            return true;
        } catch (Throwable t) {
            L.e("SIP: odebranie", t);
            return false;
        }
    }

    public void terminate() {
        try {
            if (call != null) call.terminate();
            else if (core != null) core.terminateAllCalls();
        } catch (Throwable t) {
            L.e("SIP: rozlaczenie", t);
        }
    }

    public void setMicMuted(boolean muted) {
        try {
            if (core != null) core.setMicEnabled(!muted);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Wysyla kod DTMF znak po znaku. RFC2833 idzie kanalem dzwieku (RTP), SIP INFO - kanalem
     * sygnalizacji przez serwer, wiec dociera takze wtedy, gdy dzwiek nie przechodzi.
     * Wiadomosc INFO budujemy sami, zeby tryb "RFC2833 + SIP INFO" na pewno wyslal oba.
     */
    public void sendDtmf(final String code) {
        if (call == null || code == null || code.isEmpty()) return;
        final Call target = call;
        String dm = Cfg.s("dtmf_method");
        final boolean rfc = !"info".equals(dm);
        final boolean info = "info".equals(dm) || "both".equals(dm);
        for (int i = 0; i < code.length(); i++) {
            final char ch = code.charAt(i);
            main.postDelayed(() -> {
                if (call != target || core == null) return;
                try {
                    if (rfc) target.sendDtmf(ch);
                } catch (Throwable t) {
                    L.e("SIP: DTMF RFC2833", t);
                }
                try {
                    if (info) {
                        org.linphone.core.Content ct = Factory.instance().createContent();
                        ct.setType("application");
                        ct.setSubtype("dtmf-relay");
                        ct.setUtf8Text("Signal=" + ch + "\r\nDuration=250\r\n");
                        org.linphone.core.InfoMessage im = core.createInfoMessage();
                        im.setContent(ct);
                        target.sendInfoMessage(im);
                    }
                } catch (Throwable t) {
                    L.e("SIP: DTMF INFO", t);
                }
            }, i * 400L);
        }
        L.i("SIP: wyslano DTMF " + code + " (" + (rfc && info ? "RFC2833 + SIP INFO" : info ? "SIP INFO" : "RFC2833") + ")");
    }

    public void setVideoWindow(Object window) {
        videoWindow = window;
        try {
            if (core != null) core.setNativeVideoWindowId(window);
        } catch (Throwable t) {
            L.e("SIP: okno wideo", t);
        }
    }

    private void routeToSpeaker() {
        try {
            if (core == null || call == null) return;
            for (AudioDevice d : core.getAudioDevices()) {
                if (d.getType() == AudioDevice.Type.Speaker) {
                    call.setOutputAudioDevice(d);
                    break;
                }
            }
        } catch (Throwable t) {
            L.e("SIP: glosnik", t);
        }
    }
}
