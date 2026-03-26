#!/usr/bin/env python3
import os
import posixpath
import sys
import tarfile


MACHO_MAGICS = {
    b"\xfe\xed\xfa\xce",
    b"\xce\xfa\xed\xfe",
    b"\xfe\xed\xfa\xcf",
    b"\xcf\xfa\xed\xfe",
    b"\xca\xfe\xba\xbe",
    b"\xbe\xba\xfe\xca",
    b"\xca\xfe\xba\xbf",
    b"\xbf\xba\xfe\xca",
}


def to_posix(path: str) -> str:
    return path.replace("\\", "/")


def is_macho_binary(path: str) -> bool:
    try:
        with open(path, "rb") as handle:
            return handle.read(4) in MACHO_MAGICS
    except OSError:
        return False


def should_be_executable(arcname: str, source_path: str) -> bool:
    normalized = to_posix(arcname)
    if "/Contents/MacOS/" in normalized:
        return True
    if normalized.endswith("/Contents/Resources/backend/run.sh"):
        return True
    if "/Contents/Resources/backend/jre/bin/" in normalized:
        return True
    if "/Contents/Resources/qdrant/darwin-" in normalized and normalized.endswith("/qdrant"):
        return True
    if "/Contents/Frameworks/" in normalized and is_macho_binary(source_path):
        return True
    return False


def normalized_mode(tar_info: tarfile.TarInfo, arcname: str, source_path: str) -> int:
    if tar_info.isdir():
        return 0o755
    if tar_info.issym():
        return 0o777
    if tar_info.isfile():
        if should_be_executable(arcname, source_path):
            return 0o755
        return 0o644
    return tar_info.mode


def add_path(archive: tarfile.TarFile, source_path: str, arcname: str) -> None:
    tar_info = archive.gettarinfo(source_path, arcname=arcname)
    tar_info.mode = normalized_mode(tar_info, arcname, source_path)
    if tar_info.issym():
        tar_info.linkname = to_posix(tar_info.linkname)

    if tar_info.isreg():
        with open(source_path, "rb") as handle:
            archive.addfile(tar_info, handle)
        return

    archive.addfile(tar_info)


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: create-macos-archive.py <source_dir> <output_tar_gz>", file=sys.stderr)
        return 1

    source_dir = os.path.abspath(sys.argv[1])
    output_file = os.path.abspath(sys.argv[2])

    if not os.path.isdir(source_dir):
        print(f"Source directory does not exist: {source_dir}", file=sys.stderr)
        return 1

    os.makedirs(os.path.dirname(output_file), exist_ok=True)
    if os.path.exists(output_file):
        os.remove(output_file)

    base_name = os.path.basename(source_dir.rstrip("\\/"))

    with tarfile.open(output_file, mode="w:gz", format=tarfile.PAX_FORMAT, dereference=False) as archive:
        add_path(archive, source_dir, base_name)
        for root, dirs, files in os.walk(source_dir, topdown=True, followlinks=False):
            dirs.sort()
            files.sort()
            rel_root = os.path.relpath(root, source_dir)
            root_arcname = base_name if rel_root == "." else posixpath.join(base_name, to_posix(rel_root))

            for directory in dirs:
                directory_path = os.path.join(root, directory)
                directory_arcname = posixpath.join(root_arcname, directory)
                add_path(archive, directory_path, directory_arcname)

            for file_name in files:
                file_path = os.path.join(root, file_name)
                file_arcname = posixpath.join(root_arcname, file_name)
                add_path(archive, file_path, file_arcname)

    print(output_file)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
