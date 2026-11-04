//! `jarvis-link`: the laptop daemon and the CLI that talks to it.

mod apps;
mod channel_cmd;
mod daemon;
mod desktop;
mod fcm;
mod ipc;
mod lists;
mod mark;
mod mpris;
mod pick;
mod presence;
mod viewcheck;

use std::{path::PathBuf, process::ExitCode, time::Duration};

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use serde_json::Value;
use jarvis_core::node::{Config, Net, Node, PORT};
use tracing::{info, warn};
use tracing_subscriber::EnvFilter;

use crate::{
    ipc::{Request, call},
    lists::ListOp,
};

#[derive(Parser)]
#[command(version, about = "Laptop ↔ phone: chat, files, pings, media and notifications")]
struct Cli {
    /// Daemon socket (default $JARVIS_SOCKET or $XDG_RUNTIME_DIR/jarvis.sock).
    #[arg(long, global = true)]
    socket: Option<PathBuf>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Run the daemon (normally from the systemd user unit).
    Daemon {
        /// Database and keys (default $XDG_STATE_HOME/jarvis).
        #[arg(long)]
        state_dir: Option<PathBuf>,
        /// Where received files go (default ~/Downloads).
        #[arg(long)]
        download_dir: Option<PathBuf>,
        /// The name the phone shows for this device.
        #[arg(long, default_value = "Laptop")]
        name: String,
        /// UDP port; 0 picks a free one.
        #[arg(long, default_value_t = PORT)]
        port: u16,
        /// No toasts or clipboard (for a second test instance).
        #[arg(long)]
        no_desktop: bool,
        /// Firebase service-account JSON for waking the phone (default
        /// $XDG_CONFIG_HOME/jarvis/fcm-service-account.json). Without it the daemon only redials.
        #[arg(long, env = "JARVIS_FCM_KEY")]
        fcm_key: Option<PathBuf>,
    },
    /// Show a pairing QR code and wait for the phone; or, given a code, pair with that device.
    Pair { code: Option<String> },
    /// Forget the paired device.
    Unpair,
    /// Pairing and connection state.
    Status {
        #[arg(long)]
        json: bool,
    },
    /// Send files (queued until the phone is reachable).
    Send {
        #[arg(required_unless_present_any = ["pick", "clipboard"])]
        files: Vec<PathBuf>,
        /// Choose the files in the desktop's file chooser.
        #[arg(long, conflicts_with = "clipboard")]
        pick: bool,
        /// Send the image on the clipboard.
        #[arg(long)]
        clipboard: bool,
        /// To an app channel instead of the chat: the phone keeps them with the channel, out of
        /// Downloads and the chat.
        #[arg(long, conflicts_with_all = ["pick", "clipboard"])]
        channel: Option<String>,
    },
    /// Stop sending a file (its id is in `jarvis-link json`); the phone drops what it got.
    Cancel { id: String },
    /// Put an image on the clipboard as image data (PNG), as received images are.
    Copy { file: PathBuf },
    /// Save the image on the clipboard and print its path (the chat attaches it on Ctrl+V).
    Paste,
    /// Send a chat message.
    Msg {
        #[arg(required = true)]
        text: Vec<String>,
    },
    /// Send a one-line alert that shows as a notification on the phone.
    Ping {
        #[arg(required = true)]
        text: Vec<String>,
    },
    /// Ring the phone (dropped if it can't be reached within 2 minutes).
    Ring {
        /// Stop a ring that is going on.
        #[arg(long)]
        stop: bool,
    },
    /// The phone's active notifications, as last mirrored while it was connected.
    Notifications {
        #[arg(long)]
        json: bool,
        /// Wake the phone if it isn't connected and wait for its current list (up to 25 s); the
        /// link then stays open for 2 minutes, so repeated calls see new notifications live.
        #[arg(long)]
        fresh: bool,
    },
    /// Chat snapshot for the shell UI: status, media and recent messages.
    Json {
        #[arg(long, default_value_t = 200)]
        limit: usize,
    },
    /// Mark all received messages as read.
    Read,
    /// Dial the phone now, waking it first.
    Connect,
    /// Ask the phone where its adb listens (Wi-Fi addresses, Wireless debugging, its port), waking
    /// it if needed. Prints the phone's answer as JSON; dibs reconnects adb with it.
    Adb {
        /// Turn Wireless debugging on first, if the phone app has WRITE_SECURE_SETTINGS.
        #[arg(long)]
        enable: bool,
    },
    /// Stream events as JSON lines.
    Watch,
    /// Post to a channel's thread on the phone (queued, like `msg`), ntfy-style.
    Post {
        channel: String,
        #[arg(required = true)]
        text: Vec<String>,
        /// The notification's title on the phone (default: the channel's title).
        #[arg(long)]
        title: Option<String>,
        /// Gives the post its own notification on the phone, kept apart from the channel's other
        /// posts; a later post with the same tag replaces it without alerting again.
        #[arg(long)]
        tag: Option<String>,
        /// A button under the post; the phone's tap comes back to the channel as that action.
        #[arg(long = "action", value_name = "ID:LABEL")]
        actions: Vec<String>,
        /// Loud: the phone's urgent channel, with the alarm sound (an old phone app shows it normally).
        #[arg(long)]
        loud: bool,
    },
    /// The app channels: manifests in ~/.config/jarvis/apps and channels that only have posts.
    Channels {
        #[arg(long)]
        json: bool,
        /// Read the manifests again first (and send the phone the list if it changed).
        #[arg(long)]
        reload: bool,
    },
    /// Publish a channel's view (its JSON state) from a file, or from stdin without one or with `-`.
    /// With --check, only check a view against what the phone and the panel draw (docs/PLAN.md
    /// "Blocks"): `jarvis-link view --check [<file>|-]`; exit 1 with the problems listed.
    View {
        #[arg(long)]
        check: bool,
        channel: Option<String>,
        file: Option<PathBuf>,
    },
    /// Send the channel's app an action, as the panel would (e.g. '{"action":"refresh"}').
    Action { channel: String, json: String },
    /// Create, change or remove a channel's manifest (~/.config/jarvis/apps/<name>.toml).
    Channel {
        #[command(subcommand)]
        cmd: ChannelCmd,
    },
    /// A built-in list channel (`kind = "list"`): add, ls, done, undo, rm, clear.
    List {
        channel: String,
        #[command(subcommand)]
        op: ListCmd,
    },
    /// A channel's posts, replies and actions, oldest first.
    Thread {
        channel: String,
        #[arg(long)]
        json: bool,
        #[arg(long, default_value_t = 50)]
        limit: usize,
    },
}

#[derive(Subcommand)]
enum ChannelCmd {
    /// Create a channel: `jarvis-link channel add groceries --kind list --icon shopping-cart --hue 145`.
    Add {
        /// `[a-z0-9_-]+`
        name: String,
        #[arg(long)]
        title: Option<String>,
        /// A Lucide icon name (https://lucide.dev/icons) or your own 24-grid .svg.
        #[arg(long)]
        icon: Option<String>,
        /// OKLCH hue in degrees (docs/DESIGN.md): the channel's accent and icon tile.
        #[arg(long)]
        hue: Option<f64>,
        /// One emoji or letter, shown when there is no icon.
        #[arg(long)]
        glyph: Option<String>,
        /// `#rrggbb`; the old way, `--hue` replaces it.
        #[arg(long)]
        accent: Option<String>,
        /// auto (each text by its own language, the default), ltr or rtl.
        #[arg(long)]
        dir: Option<String>,
        /// app (an app publishes the view), thread (posts only) or list (kept by Jarvis).
        #[arg(long)]
        kind: Option<String>,
        /// The shell command that starts the channel's app on demand.
        #[arg(long)]
        exec: Option<String>,
        /// both (default), phone, laptop or none (only apps use it).
        #[arg(long)]
        show: Option<String>,
        /// Accept Android share text into the channel.
        #[arg(long)]
        share: bool,
        #[arg(long)]
        no_notify: bool,
        /// Hours a ticked list item stays before it's dropped (default 24).
        #[arg(long)]
        keep_done: Option<u32>,
        /// Replace an existing manifest.
        #[arg(long)]
        force: bool,
    },
    /// Change keys of a manifest, keeping its comments: `jarvis-link channel set teen show=phone`.
    /// An empty value removes the key.
    Set {
        name: String,
        #[arg(required = true, value_name = "KEY=VALUE")]
        pairs: Vec<String>,
    },
    /// Delete a manifest. Its items stay (a thread); a list's saved state stays too, unless --purge,
    /// which also deletes the thread (here and on the phone) and works with no manifest.
    Rm {
        name: String,
        #[arg(long)]
        purge: bool,
    },
    /// Same as `jarvis-link channels`.
    Ls {
        #[arg(long)]
        json: bool,
        #[arg(long)]
        reload: bool,
    },
}

#[derive(Subcommand)]
enum ListCmd {
    /// Add items: each argument is one item (quote multi-word ones), and so is each line.
    Add {
        #[arg(required = true)]
        text: Vec<String>,
    },
    /// Show the items, newest last.
    Ls {
        /// Only those not ticked off yet.
        #[arg(long)]
        pending: bool,
        #[arg(long)]
        json: bool,
    },
    /// Tick items off (an id or a unique prefix of one).
    Done {
        #[arg(required = true)]
        ids: Vec<String>,
    },
    /// Untick items.
    Undo {
        #[arg(required = true)]
        ids: Vec<String>,
    },
    /// Delete items.
    Rm {
        #[arg(required = true)]
        ids: Vec<String>,
    },
    /// Delete the ticked items, or with --all every item.
    Clear {
        #[arg(long)]
        all: bool,
    },
}

#[tokio::main]
async fn main() -> ExitCode {
    let cli = Cli::parse();
    let sock = cli.socket.clone().unwrap_or_else(ipc::default_socket);
    match run(cli.cmd, sock).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("jarvis-link: {e:#}");
            ExitCode::FAILURE
        }
    }
}

async fn run(cmd: Cmd, sock: PathBuf) -> Result<()> {
    match cmd {
        Cmd::Daemon { state_dir, download_dir, name, port, no_desktop, fcm_key } => {
            tracing_subscriber::fmt()
                .with_env_filter(
                    EnvFilter::try_from_default_env()
                        .unwrap_or_else(|_| EnvFilter::new("info,iroh=warn,noq=warn,noq_proto=warn")),
                )
                .init();
            let state_dir = state_dir.map_or_else(default_state_dir, Ok)?;
            let lists_dir = state_dir.join("lists");
            let download_dir = download_dir.map_or_else(|| Ok(home()?.join("Downloads")), Ok::<_, anyhow::Error>)?;
            let mut cfg = Config::new(state_dir, download_dir, name);
            cfg.net = Net::Internet { port: Some(port) };
            let node = Node::start(cfg).await?;
            if !no_desktop {
                tokio::spawn(desktop::run(node.clone()));
                tokio::spawn(mpris::run(node.clone()));
            }
            let fcm_key = fcm_key.map_or_else(default_fcm_key, Ok)?;
            let unanswered = fcm::Unanswered::default();
            let wake = match fcm::Fcm::load(&fcm_key) {
                Ok(f) => {
                    info!("FCM wake on ({})", fcm_key.display());
                    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
                    tokio::spawn(fcm::run(node.clone(), f, node.events(), rx, fcm::WAKE_GAP, unanswered.clone()));
                    Some(tx)
                }
                Err(e) => {
                    warn!("FCM wake off, relying on redial: {e:#}");
                    None
                }
            };
            let clients = daemon::Clients::default();
            let apps = apps::Apps::new(apps::default_dir()?, lists_dir);
            if let Err(e) = apps.publish(&node) {
                warn!("publishing the channel list: {e:#}");
            }
            tokio::spawn(apps::run(node.clone(), clients.clone(), apps.clone()));
            let presence = presence::Tracker::default();
            tokio::spawn(presence::run(node.clone(), presence.clone()));
            let ctx = daemon::Ctx::new(node.clone(), wake, unanswered, clients, apps, presence);
            let res = tokio::select! {
                r = daemon::serve(ctx, &sock) => r,
                _ = shutdown_signal() => Ok(()),
            };
            node.shutdown().await;
            let _ = std::fs::remove_file(&sock);
            res
        }
        Cmd::Pair { code: Some(code) } => {
            let peer = call(&sock, &Request::Pair { code }).await?;
            println!("Paired with {}", peer["name"].as_str().unwrap_or("?"));
            Ok(())
        }
        Cmd::Pair { code: None } => pair_interactive(&sock).await,
        Cmd::Unpair => {
            call(&sock, &Request::Unpair).await?;
            println!("Unpaired");
            Ok(())
        }
        Cmd::Status { json } => {
            let s = call(&sock, &Request::Status).await?;
            if json {
                println!("{s}");
            } else {
                print_status(&s);
            }
            Ok(())
        }
        Cmd::Send { files, pick, clipboard, channel } => {
            let mut paths = files.iter().map(std::path::absolute).collect::<std::io::Result<Vec<_>>>()?;
            if pick {
                paths.extend(pick::choose_files().await?);
            }
            if clipboard {
                paths.push(pick::clipboard_image(&default_state_dir()?.join("pasted")).await?);
            }
            if paths.is_empty() {
                return Ok(());
            }
            let req = match channel {
                Some(channel) => Request::SendChannelFile { channel, paths },
                None => Request::Send { paths },
            };
            let sent = call(&sock, &req).await?;
            for m in sent.as_array().into_iter().flatten() {
                println!("{} {}", m["state"].as_str().unwrap_or("?"), m["file_name"].as_str().unwrap_or("?"));
            }
            Ok(())
        }
        Cmd::Cancel { id } => call(&sock, &Request::Cancel { id }).await.map(drop),
        Cmd::Copy { file } => desktop::copy_image(&file).await,
        Cmd::Paste => {
            println!("{}", pick::clipboard_image(&default_state_dir()?.join("pasted")).await?.display());
            Ok(())
        }
        Cmd::Msg { text } => print_state(call(&sock, &Request::Msg { text: text.join(" ") }).await?),
        Cmd::Ping { text } => print_state(call(&sock, &Request::Ping { text: text.join(" ") }).await?),
        Cmd::Ring { stop: false } => print_state(call(&sock, &Request::Ring).await?),
        Cmd::Ring { stop: true } => call(&sock, &Request::StopRing).await.map(drop),
        Cmd::Notifications { json, fresh } => {
            let list = call(&sock, &Request::Notifications { fresh }).await?;
            if json {
                println!("{list}");
            } else {
                for n in list.as_array().into_iter().flatten() {
                    println!("[{}] {}: {}", s(&n["app"]), s(&n["title"]), s(&n["text"]));
                }
            }
            Ok(())
        }
        Cmd::Json { limit } => {
            println!("{}", call(&sock, &Request::Json { limit }).await?);
            Ok(())
        }
        Cmd::Read => call(&sock, &Request::MarkRead).await.map(drop),
        Cmd::Connect => call(&sock, &Request::Connect).await.map(drop),
        Cmd::Adb { enable } => {
            println!("{}", call(&sock, &Request::Adb { enable }).await?);
            Ok(())
        }
        Cmd::Watch => {
            let (mut lines, _w) = ipc::watch(&sock).await?;
            while let Some(line) = lines.next_line().await? {
                println!("{line}");
            }
            Ok(())
        }
        Cmd::Post { channel, text, title, tag, actions, loud } => {
            let data = post(text.join(" "), title.as_deref(), tag.as_deref(), &actions, loud)?.to_string();
            call(&sock, &Request::AppSend { channel, data, live: false, replace: false }).await?;
            println!("queued");
            Ok(())
        }
        Cmd::Channels { json, reload } | Cmd::Channel { cmd: ChannelCmd::Ls { json, reload } } => {
            print_channels(&sock, json, reload).await
        }
        Cmd::Channel { cmd } => channel(&sock, cmd).await,
        Cmd::View { check: true, channel, file } => {
            let file = file.or(channel.map(PathBuf::from));
            let data = match file {
                Some(f) if f.as_os_str() != "-" => {
                    std::fs::read_to_string(&f).with_context(|| format!("read {}", f.display()))?
                }
                _ => std::io::read_to_string(std::io::stdin()).context("read stdin")?,
            };
            let v: Value = serde_json::from_str(&data).context("a view is JSON")?;
            let problems = viewcheck::check(&v);
            if problems.is_empty() {
                println!("ok");
                return Ok(());
            }
            for p in &problems {
                println!("{p}");
            }
            std::process::exit(1);
        }
        Cmd::View { check: false, channel, file } => {
            let channel = channel.context("which channel? `jarvis-link view <channel> [<file>|-]`")?;
            let data = match file {
                Some(f) if f.as_os_str() != "-" => {
                    std::fs::read_to_string(&f).with_context(|| format!("read {}", f.display()))?
                }
                _ => std::io::read_to_string(std::io::stdin()).context("read stdin")?,
            };
            let v: Value = serde_json::from_str(&data).context("a view is JSON")?;
            anyhow::ensure!(v.is_object(), "a view is a JSON object");
            // Published anyway (the renderers skip what they don't know), but said.
            for p in viewcheck::check(&v) {
                eprintln!("jarvis-link view: {p}");
            }
            let data = v.to_string();
            call(&sock, &Request::AppSend { channel, data, live: false, replace: true }).await?;
            println!("queued");
            Ok(())
        }
        Cmd::Action { channel, json } => call(&sock, &Request::AppAction { channel, data: json }).await.map(drop),
        Cmd::List { channel, op } => {
            let (json, op) = match op {
                ListCmd::Add { text } => (false, ListOp::Add { text: text.join("\n") }),
                ListCmd::Ls { pending, json } => (json, ListOp::Ls { pending }),
                ListCmd::Done { ids } => (false, ListOp::Done { ids }),
                ListCmd::Undo { ids } => (false, ListOp::Undo { ids }),
                ListCmd::Rm { ids } => (false, ListOp::Rm { ids }),
                ListCmd::Clear { all } => (false, ListOp::Clear { done_only: !all }),
            };
            let ls = matches!(op, ListOp::Ls { .. });
            let reply = call(&sock, &Request::List { channel, op }).await?;
            if json {
                println!("{reply}");
            } else if ls {
                let items = reply.as_array().map(Vec::as_slice).unwrap_or_default();
                let ids: Vec<&str> = items.iter().map(|i| s(&i["id"])).collect();
                for (i, short) in items.iter().zip(short_ids(&ids)) {
                    println!("{} {short}  {}", if i["done"] == true { "✓" } else { "·" }, s(&i["text"]));
                }
            } else {
                println!("{reply}");
            }
            Ok(())
        }
        Cmd::Thread { channel, json, limit } => {
            let items = call(&sock, &Request::Thread { channel, limit }).await?;
            if json {
                println!("{items}");
            } else {
                for i in items.as_array().into_iter().flatten() {
                    println!("{}", thread_line(i));
                }
            }
            Ok(())
        }
    }
}

/// A thread post: `{"post":{"text","actions":[{"id","label"}]?}}` from `ID:LABEL` pairs.
fn post(text: String, title: Option<&str>, tag: Option<&str>, actions: &[String], loud: bool) -> Result<Value> {
    let mut p = serde_json::json!({ "text": text });
    for (k, v) in [("title", title), ("tag", tag)] {
        if let Some(v) = v.filter(|v| !v.is_empty()) {
            p[k] = v.into();
        }
    }
    if !actions.is_empty() {
        let list = actions
            .iter()
            .map(|a| {
                let (id, label) = a.split_once(':').filter(|(i, l)| !i.is_empty() && !l.is_empty()).with_context(
                    || format!("--action {a:?}: expected ID:LABEL"),
                )?;
                Ok(serde_json::json!({ "id": id, "label": label }))
            })
            .collect::<Result<Vec<_>>>()?;
        p["actions"] = Value::Array(list);
    }
    if loud {
        p["loud"] = true.into();
    }
    Ok(serde_json::json!({ "post": p }))
}

/// `who: what` for one thread item: a post, a reply's text, or an action and its value.
fn thread_line(i: &Value) -> String {
    let d = &i["data"];
    let who = d["from"].as_str().unwrap_or(if i["from_me"] == true { "laptop" } else { "phone" });
    let what = if let Some(t) = d["post"]["text"].as_str().or(d["text"].as_str()) {
        t.to_string()
    } else if let Some(a) = d["action"].as_str() {
        let v = d.get("value").or(d.get("fields")).map(|v| format!(" {v}")).unwrap_or_default();
        format!("[{a}]{}", v.chars().take(200).collect::<String>())
    } else {
        d.to_string()
    };
    format!("{who}: {what}")
}

async fn pair_interactive(sock: &std::path::Path) -> Result<()> {
    let (mut events, _w) = ipc::watch(sock).await?;
    let offer = call(sock, &Request::PairOffer).await?;
    let code = offer["code"].as_str().context("no pairing code")?;
    let qr = std::process::Command::new("qrencode")
        .args(["-t", "ANSIUTF8", "-m", "2", code])
        .status();
    if !qr.is_ok_and(|s| s.success()) {
        eprintln!("(install qrencode to see a QR code)");
    }
    println!("Scan with Jarvis on the phone, or run `jarvis-link pair {code}` on the other device.");
    println!("Valid for 5 minutes, once.");
    let wait = async {
        while let Some(line) = events.next_line().await? {
            let e: Value = serde_json::from_str(&line)?;
            if e["event"] == "paired" {
                return Ok(e["peer"]["name"].as_str().unwrap_or("?").to_string());
            }
        }
        anyhow::bail!("daemon went away")
    };
    let name = tokio::time::timeout(Duration::from_secs(300), wait)
        .await
        .context("pairing code expired")??;
    println!("Paired with {name}");
    Ok(())
}

fn print_status(st: &Value) {
    println!("This device  {} ({})", s(&st["name"]), s(&st["id"]));
    match st["peer"].as_object() {
        Some(p) => {
            let link = match (st["connected"] == true, st["wake_unanswered"] == true) {
                (true, _) => "connected",
                (false, true) => "not connected (it didn't answer the last wake)",
                (false, false) => "not connected",
            };
            println!("Paired with  {} ({}), {link}", s(&p["name"]), s(&p["id"]));
        }
        None => println!("Not paired (run `jarvis-link pair`)"),
    }
    println!("Queued {}, unread {}", st["queued"], st["unread"]);
}

fn print_state(m: Value) -> Result<()> {
    println!("{}", s(&m["state"]));
    Ok(())
}

async fn print_channels(sock: &std::path::Path, json: bool, reload: bool) -> Result<()> {
    let list = call(sock, &if reload { Request::ChannelsReload } else { Request::Channels }).await?;
    if json {
        println!("{list}");
    } else {
        for c in list.as_array().into_iter().flatten() {
            let badge = c["badge"].as_u64().map(|b| format!("  ({b})")).unwrap_or_default();
            let show = c["show"].as_str().filter(|s| *s != "both").map(|s| format!(" ({s} only)")).unwrap_or_default();
            println!("{} {:<14} {} [{}]{show}{badge}", s(&c["glyph"]), s(&c["name"]), s(&c["title"]), s(&c["kind"]));
        }
    }
    Ok(())
}

/// `jarvis-link channel add|set|rm`: edit the manifest, then tell the daemon (when it runs).
async fn channel(sock: &std::path::Path, cmd: ChannelCmd) -> Result<()> {
    let dir = apps::default_dir()?;
    let name = match cmd {
        ChannelCmd::Add { name, title, icon, hue, glyph, accent, dir: d, kind, exec, show, share, no_notify, keep_done, force } => {
            let new = channel_cmd::New { title, icon, hue, glyph, accent, dir: d, kind, exec, show, share, no_notify, keep_done };
            println!("wrote {}", channel_cmd::add(&dir, &name, &new, force)?.display());
            name
        }
        ChannelCmd::Set { name, pairs } => {
            println!("updated {}", channel_cmd::set(&dir, &name, &pairs)?.display());
            name
        }
        ChannelCmd::Rm { name, purge } => {
            let had = channel_cmd::rm(&dir, &name)?;
            if !had && !purge {
                anyhow::bail!("{name} has no manifest (--purge deletes a thread that has none)");
            }
            let state = default_state_dir()?.join("lists").join(format!("{name}.json"));
            if purge {
                let _ = std::fs::remove_file(&state);
                match call(sock, &Request::DropThread { channel: name.clone() }).await {
                    Ok(v) => println!("deleted {name}'s thread ({} items); the phone drops it on its next link", v["dropped"]),
                    Err(e) => anyhow::bail!("{name}: the thread wasn't deleted ({e:#}); is the daemon running?"),
                }
            } else if state.exists() {
                println!("kept its list state ({}); --purge deletes it", state.display());
            }
            if had {
                println!("removed {name}{}", if purge { "" } else { "; its items stay on the phone as a thread" });
            }
            name
        }
        ChannelCmd::Ls { .. } => unreachable!("handled by the caller"),
    };
    match call(sock, &Request::ChannelsReload).await {
        Ok(_) => println!("{name}: the daemon reloaded"),
        Err(e) => println!("{name}: not reloaded ({e:#}); the daemon reads it at start"),
    }
    Ok(())
}

/// The shortest prefix of each id (at least 6 characters) that no other id shares, so what
/// `jarvis-link list ls` prints can be pasted into `done`, `undo` and `rm`.
fn short_ids<'a>(ids: &[&'a str]) -> Vec<&'a str> {
    ids.iter()
        .map(|id| {
            let ends = id.char_indices().map(|(i, c)| i + c.len_utf8()).filter(|&e| e >= 6.min(id.len()));
            ends.map(|e| &id[..e])
                .find(|p| ids.iter().filter(|o| o.starts_with(p)).count() == 1)
                .unwrap_or(id)
        })
        .collect()
}

fn s(v: &Value) -> &str {
    v.as_str().unwrap_or("")
}

fn home() -> Result<PathBuf> {
    std::env::var_os("HOME").map(PathBuf::from).context("HOME is not set")
}

/// `$XDG_CONFIG_HOME`, or `~/.config`.
fn config_dir() -> Result<PathBuf> {
    Ok(match std::env::var_os("XDG_CONFIG_HOME") {
        Some(d) => PathBuf::from(d),
        None => home()?.join(".config"),
    })
}

fn default_fcm_key() -> Result<PathBuf> {
    Ok(config_dir()?.join(jarvis_core::compat::DIR).join("fcm-service-account.json"))
}

fn default_state_dir() -> Result<PathBuf> {
    Ok(match std::env::var_os("XDG_STATE_HOME") {
        Some(d) => PathBuf::from(d).join(jarvis_core::compat::DIR),
        None => home()?.join(".local/state").join(jarvis_core::compat::DIR),
    })
}

async fn shutdown_signal() {
    use tokio::signal::unix::{SignalKind, signal};
    let mut term = signal(SignalKind::terminate()).expect("SIGTERM handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term.recv() => {}
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn list_ids_are_shortened_to_unique_prefixes() {
        let ids = ["3d6dc340-aaaa", "1~3d6dc340-aaaa", "3d6dd111-bbbb", "ab"];
        assert_eq!(short_ids(&ids), ["3d6dc3", "1~3d6d", "3d6dd1", "ab"]);
        assert_eq!(short_ids(&["abcdef1", "abcdef2"]), ["abcdef1", "abcdef2"]);
    }

    #[test]
    fn posts_and_thread_lines() {
        let p = post("hi".into(), None, None, &["ok:OK".into(), "no:Not now".into()], false).unwrap();
        assert_eq!(p, json!({"post": {"text": "hi", "actions": [{"id": "ok", "label": "OK"}, {"id": "no", "label": "Not now"}]}}));
        assert_eq!(post("hi".into(), None, None, &[], false).unwrap(), json!({"post": {"text": "hi"}}));
        assert!(post("hi".into(), None, None, &["nolabel".into()], false).is_err());
        let q = post("Merge now?".into(), Some("dibs: web"), Some("q-12"), &["yes:Merge".into()], false).unwrap();
        assert_eq!(
            q,
            json!({"post": {"text": "Merge now?", "title": "dibs: web", "tag": "q-12", "actions": [{"id": "yes", "label": "Merge"}]}})
        );
        assert_eq!(post("hi".into(), Some(""), Some(""), &[], false).unwrap(), json!({"post": {"text": "hi"}}), "empty flags are left out");

        let l = post("Done".into(), None, Some("task:7"), &[], true).unwrap();
        assert_eq!(l, json!({"post": {"text": "Done", "tag": "task:7", "loud": true}}));

        assert_eq!(thread_line(&json!({"from_me": true, "data": p})), "laptop: hi");
        assert_eq!(thread_line(&json!({"from_me": false, "data": {"text": "yes"}})), "phone: yes");
        let tap = json!({"from_me": false, "data": {"action": "ok", "from": "laptop", "value": {"item": 3}}});
        assert_eq!(thread_line(&tap), r#"laptop: [ok] {"item":3}"#);
    }
}
