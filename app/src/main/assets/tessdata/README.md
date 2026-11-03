# Bundled Tesseract traineddata

`eng.traineddata` is taken from the `tesseract-ocr/tessdata_fast` project
(https://github.com/tesseract-ocr/tessdata_fast), which is released under the
Apache License 2.0.

The `fast` models are used deliberately: they are roughly a quarter the size of the
`best` models and materially faster on a phone, at a small accuracy cost that is
acceptable for short screen selections.

Lenshift copies this file into app-private storage on first use and never downloads
additional language data at runtime.

To add a language, drop `<code>.traineddata` into this directory from
`tessdata_fast` and add the code to the OCR language picker in Settings.
