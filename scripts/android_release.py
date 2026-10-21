"""Build metadata and signed APK packaging, shared by local builds and Actions."""
import argparse
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATES = ROOT / "android/signing/certificates.json"


def metadata(tag=None):
    source = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'versionName\s*=\s*"(\d+\.\d+\.\d+)"', source).group(1)
    code = int(re.search(r"versionCode\s*=\s*(\d+)", source).group(1))
    if tag is not None and tag != f"v{version}":
        raise ValueError("Release tag must match the version declared in Gradle")
    return {"versionName": version, "versionCode": code, "tag": f"v{version}", "applicationId": "app.mediary"}


def run(command, env=None):
    result = subprocess.run([str(item) for item in command], env=env, capture_output=True)
    if result.returncode:
        # 签名工具失败时不能展开环境变量或私有配置，日志只报告失败步骤。
        raise RuntimeError(f"Release tool failed: {Path(command[0]).name}")
    return result.stdout.decode("utf-8", "replace")


def tools(sdk):
    root = Path(sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ROOT / "work/tools/android-sdk")
    build = root / "build-tools/35.0.0"
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") if os.environ.get("JAVA_HOME") else shutil.which("java")
    if not java or not (build / "lib/apksigner.jar").is_file():
        raise ValueError("JDK and Android Build Tools 35.0.0 are required")
    return [str(java), "-jar", str(build / "lib/apksigner.jar")], build


@contextlib.contextmanager
def signing_keys():
    config = os.environ.get("MEDIARY_SIGNING_CONFIG")
    if config:
        path = Path(config).resolve()
        data = json.loads(path.read_text(encoding="utf-8"))
        for entry in data.values():
            entry["file"] = str(path.parent / entry["file"])
        yield data
        return
    bundle = os.environ.get("ANDROID_SIGNING_BUNDLE")
    if not bundle:
        raise ValueError("Private signing configuration is missing")
    data = json.loads(base64.b64decode(bundle, validate=True))
    # 临时密钥只写入 runner 的私有临时目录，不进入工作树或发布产物。
    with tempfile.TemporaryDirectory(prefix="mediary-signing-", dir=os.environ.get("RUNNER_TEMP")) as folder:
        for name in ("legacy", "current"):
            entry = data[name]
            file = Path(folder) / f"{name}.p12"
            file.write_bytes(base64.b64decode(entry.pop("data"), validate=True))
            file.chmod(0o600)
            entry["file"] = str(file)
        yield data


def verify(apk, sdk=None):
    signer, build = tools(sdk)
    trusted = json.loads(CERTIFICATES.read_text(encoding="utf-8"))
    run(signer + ["verify", str(apk)])
    # Android 8 使用原签名；Android 9 起使用新签名及旧证书签署的迁移证明。
    for low, high, key in ((26, 27, "legacySha256"), (28, 35, "currentSha256")):
        output = run(signer + ["verify", "--min-sdk-version", low, "--max-sdk-version", high, "--print-certs", apk])
        digest = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", output)
        if not digest or digest.group(1).lower() != trusted[key]:
            raise ValueError("APK certificate does not match the committed certificate fingerprint")
    suffix = ".exe" if os.name == "nt" else ""
    badging = run([build / f"aapt2{suffix}", "dump", "badging", apk])
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or "application-debuggable" in badging or "application-testOnly" in badging:
        raise ValueError("APK must be a non-debuggable, installable release build")
    expected = metadata()
    if package.groups() != (expected["applicationId"], str(expected["versionCode"]), expected["versionName"]):
        raise ValueError("APK package or version does not match the source")
    run([build / f"zipalign{suffix}", "-c", "-P", "16", "4", apk])
    return expected | {"signingCertificateSha256": trusted["currentSha256"], "legacyCertificateSha256": trusted["legacySha256"], "rotationMinSdk": trusted["rotationMinSdk"]}


def sign(output, tag, sdk):
    info = metadata(tag)
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    apk = output / "mediary.apk"
    source = ROOT / "android/app/build/outputs/apk/release/app-release-unsigned.apk"
    built = json.loads((source.parent / "output-metadata.json").read_text(encoding="utf-8"))
    element = built["elements"][0]
    if element["versionName"] != info["versionName"] or element["versionCode"] != info["versionCode"]:
        raise ValueError("Unsigned APK is stale; rebuild the release variant")
    signer, _ = tools(sdk)
    with signing_keys() as keys:
        env = os.environ.copy()
        env.pop("ANDROID_SIGNING_BUNDLE", None)
        command = signer + ["sign", "--out", apk, "--lineage", ROOT / "android/signing/lineage.bin", "--rotation-min-sdk-version", "28", "--v4-signing-enabled", "false", "--debuggable-apk-permitted", "false"]
        for index, name in enumerate(("legacy", "current")):
            if index:
                command += ["--next-signer"]
            entry = keys[name]
            variable = f"MEDIARY_SIGN_{name.upper()}_PASSWORD"
            env[variable] = entry["password"]
            command += ["--ks", entry["file"], "--ks-key-alias", entry["alias"], "--ks-pass", f"env:{variable}", "--key-pass", f"env:{variable}"]
        run(command + [source], env)
    info = verify(apk, sdk)
    info["commit"] = run(["git", "-C", ROOT, "rev-parse", "HEAD"]).strip()
    info["sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
    (output / "release-info.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    (output / "SHA256SUMS").write_text(f'{info["sha256"]}  mediary.apk\n', encoding="ascii")
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("metadata", "sign", "verify"))
    parser.add_argument("--tag")
    parser.add_argument("--sdk-root")
    parser.add_argument("--output", default=str(ROOT / "outputs/release"))
    parser.add_argument("--apk")
    args = parser.parse_args()
    try:
        if args.command == "metadata":
            result = metadata(args.tag)
        elif args.command == "sign":
            result = sign(args.output, args.tag, args.sdk_root)
        else:
            result = verify(Path(args.apk), args.sdk_root)
        print(json.dumps(result))
    except (ValueError, RuntimeError, OSError, KeyError, TypeError):
        raise SystemExit("Android release validation failed; private signing details withheld") from None


if __name__ == "__main__":
    main()
