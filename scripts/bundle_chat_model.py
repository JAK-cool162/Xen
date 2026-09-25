"""Make the all-in-one mod jar: the mod with its chat model inside.

    python scripts/bundle_chat_model.py dist/xen-companion-0.5.0-alpha+mc1.21.11.jar smollm2-360m-instruct-q8_0.gguf

writes dist/xen-companion-0.5.0-alpha+mc1.21.11-with-chat.jar: every entry of the mod jar as it is, plus the model at
assets/xen/model/ (stored, not compressed, so the mod unpacks it with a plain copy). The mod checks the model's SHA-256
when it unpacks it, so a wrong or damaged file is refused here already.
"""
import hashlib
import os
import sys
import zipfile

SHA256 = "48ab3034d0dd401fbc721eb1df3217902fee7dab9078992d66431f09b7750201"   # the same as xen.mod.talk.Chat.SHA256
MODEL = "smollm2-360m-instruct-q8_0.gguf"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def bundle(jar, model, out=None):
    if sha256(model) != SHA256:
        raise SystemExit(f"{model} is not the chat model the mod expects (SHA-256 differs)")
    out = out or jar[:-len(".jar")] + "-with-chat.jar"
    with zipfile.ZipFile(jar) as src, zipfile.ZipFile(out, "w") as dst:
        for item in src.infolist():
            dst.writestr(item, src.read(item.filename))
        dst.write(model, "assets/xen/model/" + MODEL, compress_type=zipfile.ZIP_STORED)
    return out


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    made = bundle(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    print(f"{made} ({os.path.getsize(made) / 1e6:.0f} MB)")
