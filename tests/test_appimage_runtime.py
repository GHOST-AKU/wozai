"""A removed continuous-release asset must not weaken the pinned runtime check."""
import hashlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

path=Path(__file__).resolve().parents[1]/'desktop/tools/build-appimage.py'
module_spec=importlib.util.spec_from_file_location('appimage_builder',path)
builder=importlib.util.module_from_spec(module_spec);module_spec.loader.exec_module(builder)

class RuntimeDownloadTests(unittest.TestCase):
    content=b'\x7fELF\x00\x00\x00\x00AI\x02'
    def spec(self,archive=True):
        value={'url':'https://upstream.invalid/runtime','bytes':len(self.content),
               'sha256':hashlib.sha256(self.content).hexdigest()}
        if archive:value['archive_url']='https://archive.invalid/previous.AppImage'
        return value
    def download(self,spec):
        self.assertTrue(hasattr(builder,'download_runtime'),'Pinned runtime downloader cannot recover a deleted upstream asset')
        return builder.download_runtime(spec)
    def test_deleted_asset_recovers_only_matching_archived_prefix(self):
        missing=HTTPError(self.spec()['url'],404,'Not Found',None,None)
        with patch.object(builder.urllib.request,'urlopen',side_effect=[missing,io.BytesIO(self.content+b'old application payload')]):
            self.assertEqual(self.download(self.spec()),self.content)
    def test_primary_download_keeps_exact_size_and_hash(self):
        with patch.object(builder.urllib.request,'urlopen',return_value=io.BytesIO(self.content)):
            self.assertEqual(self.download(self.spec()),self.content)
    def test_corrupt_archive_is_rejected(self):
        missing=HTTPError(self.spec()['url'],404,'Not Found',None,None)
        with patch.object(builder.urllib.request,'urlopen',side_effect=[missing,io.BytesIO(b'bad prefix!')]):
            with self.assertRaises(SystemExit):self.download(self.spec())
    def test_extra_primary_bytes_are_rejected(self):
        with patch.object(builder.urllib.request,'urlopen',return_value=io.BytesIO(self.content+b'x')):
            with self.assertRaises(SystemExit):self.download(self.spec())
    def test_other_http_errors_are_not_hidden(self):
        forbidden=HTTPError(self.spec()['url'],403,'Forbidden',None,None)
        with patch.object(builder.urllib.request,'urlopen',side_effect=forbidden):
            with self.assertRaises(HTTPError):self.download(self.spec())
    def test_missing_asset_without_archive_still_fails(self):
        missing=HTTPError(self.spec()['url'],404,'Not Found',None,None)
        with patch.object(builder.urllib.request,'urlopen',side_effect=missing):
            with self.assertRaises(HTTPError):self.download(self.spec(False))

if __name__=='__main__':unittest.main()
