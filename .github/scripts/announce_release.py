#!/usr/bin/env python3
"""
Post two Discord webhook messages after all loaders are published:
  1. Banner image (with optional @role ping when discord_ping=true)
  2. Formatted changelog embed with CurseForge/Modrinth links

Reads gradle.properties for metadata and CHANGELOG.md for the latest body.
Skips silently if DISCORD_WEBHOOK is not set, so non-release runs are no-ops.

Called from .github/workflows/release.yml AFTER all per-loader publishMods
tasks have completed, so it only fires when every jar uploaded successfully.
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# Edit this string if you add or drop a loader. Hardcoded on purpose so the
# Discord message wording is reviewed when loader support changes.
LOADERS = "Fabric, NeoForge & Forge"


def read_properties(path):
    out = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        s = line.strip()
        if not s or s.startswith('#') or '=' not in s:
            continue
        k, _, v = s.partition('=')
        out[k.strip()] = v.strip()
    return out


def extract_changelog(path, version):
    if not path.exists():
        return ''
    content = path.read_text(encoding='utf-8')
    # Same regex shape used by post_release_bump.py and the original build.gradle.
    m = re.search(
        rf'## \[{re.escape(version)}\][^\n]*\r?\n(.*?)(?=\r?\n---|\r?\n## \[|\Z)',
        content,
        re.DOTALL,
    )
    return m.group(1).strip() if m else ''


def post(webhook, payload, label='message'):
    """POST a JSON payload to Discord. On HTTP error, print the response body
    and a payload preview to stderr before re-raising — Discord's 4xx bodies
    are the only signal we get about what was wrong with the request.
    """
    data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(
        webhook,
        data=data,
        method='POST',
        headers={
            'Content-Type': 'application/json; charset=utf-8',
            # Discord rejects requests with the default 'Python-urllib/X.Y'
            # User-Agent (403 Forbidden). Set a custom UA per their docs:
            # https://discord.com/developers/docs/reference#user-agent
            'User-Agent': 'MoogsPathsReleaseBot (https://github.com/FinnSetchell/MoogsPaths, 1.0)',
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            print(f'  -> {label} accepted (HTTP {r.status})')
    except urllib.error.HTTPError as e:
        err_body = ''
        try:
            err_body = e.read().decode('utf-8', errors='replace')
        except Exception:
            pass
        sys.stderr.write(f'Discord POST failed: HTTP {e.code} {e.reason}\n')
        if err_body:
            sys.stderr.write(f'  response body: {err_body}\n')
        preview = json.dumps(payload, ensure_ascii=False, indent=2)
        if len(preview) > 2000:
            preview = preview[:2000] + '\n  ...(truncated)'
        sys.stderr.write(f'  request payload ({label}):\n{preview}\n')
        raise


def main():
    webhook = os.environ.get('DISCORD_WEBHOOK', '').strip()
    if not webhook:
        print('DISCORD_WEBHOOK not set - skipping announcement.', file=sys.stderr)
        return 0

    if os.environ.get('PUBLISH_DRY_RUN', '').lower() == 'true':
        dry = os.environ.get('DISCORD_WEBHOOK_DRY_RUN', '').strip()
        if not dry:
            print('PUBLISH_DRY_RUN=true but no DISCORD_WEBHOOK_DRY_RUN set - skipping.', file=sys.stderr)
            return 0
        webhook = dry

    props = read_properties(Path('gradle.properties'))

    mod_name = props.get('mod_name', '')
    version = props.get('version', '')
    minecraft_version = props.get('minecraft_version', '')
    publish_mc_start = props.get('publish_mc_start', '')
    publish_mc_end = props.get('publish_mc_end', '')
    mod_curseforge = props.get('mod_curseforge', '')
    mod_modrinth = props.get('mod_modrinth', '')

    role_id = props.get('discord_role_id', '').strip()
    banner_url = props.get('discord_banner_url', '').strip()
    avatar_url = props.get('discord_avatar_url', '').strip()
    embed_color_hex = props.get('discord_embed_color', '#8B6914').lstrip('#')
    ping_enabled = props.get('discord_ping', 'true').strip().lower() == 'true'

    username = "Moog's Mods"

    # --- Message 1: banner image, with optional role ping ---
    if banner_url:
        if ping_enabled and role_id:
            content = f'<@&{role_id}>\n{banner_url}'
            allowed_mentions = {'parse': [], 'roles': [role_id]}
        else:
            content = banner_url
            allowed_mentions = {'parse': []}

        payload = {
            'username': username,
            'content': content,
            'allowed_mentions': allowed_mentions,
        }
        if avatar_url:
            payload['avatar_url'] = avatar_url

        print('posting banner message')
        post(webhook, payload, label='banner')
    else:
        print('discord_banner_url not set - skipping banner message.', file=sys.stderr)

    # Small pause: webhooks share a rate-limit bucket; back-to-back posts can 429.
    time.sleep(0.5)

    # --- Message 2: formatted changelog embed ---
    changelog_body = extract_changelog(Path('CHANGELOG.md'), version)
    if not changelog_body:
        print(f'no CHANGELOG.md section found for version {version!r} - embed will have empty changelog.', file=sys.stderr)

    if publish_mc_start == publish_mc_end:
        version_range = publish_mc_start
    else:
        version_range = f'{publish_mc_start} - {publish_mc_end}'

    description = (
        f'## **{mod_name} {version}** has been released! \U0001F389\n\n'
        f'**{mod_name} {version}-{minecraft_version}** | {LOADERS}\n'
        f'Versions: {version_range}\n\n'
        f'### \U0001F4DD **Changelog:**\n'
        f'{changelog_body}\n\n'
        f'<:curseforge:1132291568305459250> [CurseForge]({mod_curseforge}) | '
        f'<:modrinth:1132291566019563550> [Modrinth]({mod_modrinth})'
    )

    # Discord caps embed description at 4096 chars.
    if len(description) > 4096:
        truncated_marker = '\n\n...changelog truncated; see CHANGELOG.md on GitHub.'
        keep = 4096 - len(truncated_marker)
        description = description[:keep] + truncated_marker

    try:
        color = int(embed_color_hex, 16)
    except ValueError:
        color = 0x8B6914

    payload = {
        'username': username,
        'embeds': [{
            'description': description,
            'color': color,
        }],
        'allowed_mentions': {'parse': []},
    }
    if avatar_url:
        payload['avatar_url'] = avatar_url

    print('posting changelog message')
    post(webhook, payload, label='changelog')
    return 0


if __name__ == '__main__':
    sys.exit(main())
