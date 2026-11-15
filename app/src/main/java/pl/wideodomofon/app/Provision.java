package pl.wideodomofon.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.UUID;

/** Zapisuje w ustawieniach konto SIP pobrane z serwera Domofon SIP (patrz {@link ServerLink}). */
public final class Provision {
    private Provision() {}

    /** Staly identyfikator tabletu - serwer rozpoznaje po nim ponowne parowanie tego samego urzadzenia. */
    static String deviceUid(Context ctx) {
        String id = null;
        try {
            id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (id == null || id.length() < 8) {
            SharedPreferences p = ctx.getSharedPreferences("provision", Context.MODE_PRIVATE);
            id = p.getString("uid", null);
            if (id == null) {
                id = UUID.randomUUID().toString().replace("-", "");
                p.edit().putString("uid", id).apply();
            }
        }
        return "wd-" + id;
    }

    /** Pobiera konto i zapisuje je w ustawieniach. Zwraca opis dla uzytkownika. Nie wolac z watku glownego. */
    public static String apply(Context ctx, String link, String name) throws Exception {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = (Cfg.isPhone(ctx) ? "Telefon " : "Tablet ") + Build.MODEL;
        ServerLink.Account a = ServerLink.fetch(link, deviceUid(ctx), n);
        JSONObject cfg = new JSONObject();
        cfg.put("sip_enabled", true);
        cfg.put("sip_server", a.server + ":" + a.port);
        cfg.put("sip_user", a.user);
        cfg.put("sip_pass", a.pass);
        cfg.put("sip_transport", a.transport);
        cfg.put("sip_expires", 120);
        String extras = extras(a, cfg);
        Cfg.update(cfg);
        SharedPreferences p = ctx.getSharedPreferences("provision", Context.MODE_PRIVATE);
        if (a.configLink != null) p.edit().putString("link", a.configLink).apply();
        L.i("Serwer: pobrano konto " + a.user + "@" + a.server + ":" + a.port + " (" + a.transport + ")" + extras);
        StringBuilder sb = new StringBuilder("Pobrano konto ").append(a.user);
        if (!a.extension.isEmpty()) sb.append(" (numer ").append(a.extension).append(")");
        if (!a.group.isEmpty()) sb.append(", grupa ").append(a.group);
        sb.append(".").append(extras);
        return sb.append(" Rejestracja nastąpi za chwilę.").toString();
    }

    /**
     * Ustawienia dodatkowe z pliku konfiguracyjnego serwera: grupa z podgladem, kod odebrania, kody przekaznikow.
     * Wazne: kod odebrania ustawiamy tylko przy wlaczonym podgladzie - w zwyklym polaczeniu trafilby on
     * prosto do bramofonu i mogl otworzyc furtke.
     */
    private static String extras(ServerLink.Account a, JSONObject cfg) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (a.preview != null) {
            boolean on = a.preview;
            String code = on && a.answerDtmf != null ? a.answerDtmf : "";
            cfg.put("preview_answer", on);
            cfg.put("answer_dtmf", code);
            if (on) cfg.put("dtmf_method", "rfc2833");
            sb.append(" Podgląd grupowy: ").append(on ? "włączony" : "wyłączony");
            if (!code.isEmpty()) sb.append(", kod odebrania ").append(code);
            sb.append(".");
        }
        if (!a.relays.isEmpty()) {
            String[] r1 = a.relays.get(0);
            cfg.put("dtmf_code", r1[1]);
            sb.append(" Furtka: kod ").append(r1[1]);
            if (a.relays.size() > 1) {
                String[] r2 = a.relays.get(1);
                cfg.put("dtmf_code2", r2[1]);
                cfg.put("gate2_label", r2[0].isEmpty() ? "Brama" : r2[0]);
                sb.append(", ").append(r2[0].isEmpty() ? "brama" : r2[0]).append(": kod ").append(r2[1]);
            } else {
                cfg.put("dtmf_code2", "");
            }
            sb.append(".");
        }
        return sb.toString();
    }

    /**
     * Ponowne pobranie pliku konfiguracyjnego (po zmianie ustawien grupy w panelu serwera).
     * Zwraca true, gdy zmienilo sie konto SIP i trzeba zrestartowac rejestracje. Nie wolac z watku glownego.
     */
    public static boolean refresh(Context ctx) {
        String link = ctx.getSharedPreferences("provision", Context.MODE_PRIVATE).getString("link", null);
        if (link == null || !Cfg.b("sip_enabled")) return false;
        try {
            ServerLink.Account a = ServerLink.fetch(link, deviceUid(ctx), "");
            JSONObject cfg = new JSONObject();
            JSONObject before = Cfg.all();
            String server = a.server + ":" + a.port;
            boolean sipChanged = !server.equals(Cfg.s("sip_server")) || !a.user.equals(Cfg.s("sip_user"))
                    || !a.pass.equals(Cfg.s("sip_pass")) || !a.transport.equals(Cfg.s("sip_transport"));
            if (sipChanged) {
                cfg.put("sip_server", server);
                cfg.put("sip_user", a.user);
                cfg.put("sip_pass", a.pass);
                cfg.put("sip_transport", a.transport);
            }
            String extras = extras(a, cfg);
            boolean changed = false;
            java.util.Iterator<String> it = cfg.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!String.valueOf(cfg.opt(k)).equals(String.valueOf(before.opt(k)))) changed = true;
            }
            if (changed) {
                Cfg.update(cfg);
                L.i("Serwer: odswiezono konfiguracje." + extras + (sipChanged ? " Zmienilo sie konto SIP." : ""));
            }
            return sipChanged;
        } catch (Throwable t) {
            L.i("Serwer: nie udalo sie odswiezyc konfiguracji (" + t.getMessage() + ")");
            return false;
        }
    }
}
