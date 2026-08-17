/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/File.h"

extern "C" {
#include <mupdf/pdf.h>
}

#include "BookBlob.h"
#include "PdfSidecar.h"

static pdf_obj* SidecarCatalog(fz_context* ctx, pdf_document* pdf) {
    pdf_obj* root = nullptr;
    fz_var(root);
    fz_try(ctx) {
        root = pdf_resolve_indirect(ctx, pdf_dict_get(ctx, pdf_trailer(ctx, pdf), PDF_NAME(Root)));
    }
    fz_catch(ctx) {
        fz_ignore_error(ctx);
        root = nullptr;
    }
    return pdf_is_dict(ctx, root) ? root : nullptr;
}

static pdf_obj* SidecarPrivate(fz_context* ctx, pdf_document* pdf) {
    pdf_obj* res = nullptr;
    fz_var(res);
    fz_try(ctx) {
        pdf_obj* root = SidecarCatalog(ctx, pdf);
        pdf_obj* pieces = pdf_resolve_indirect(ctx, pdf_dict_gets(ctx, root, "PieceInfo"));
        pdf_obj* app = pdf_resolve_indirect(ctx, pdf_dict_gets(ctx, pieces, kSidecarApp));
        res = pdf_resolve_indirect(ctx, pdf_dict_gets(ctx, app, "Private"));
    }
    fz_catch(ctx) {
        fz_ignore_error(ctx);
        res = nullptr;
    }
    return pdf_is_dict(ctx, res) ? res : nullptr;
}

static pdf_obj* SidecarBlobObj(fz_context* ctx, pdf_document* pdf) {
    pdf_obj* res = nullptr;
    fz_var(res);
    fz_try(ctx) {
        res = pdf_dict_gets(ctx, SidecarPrivate(ctx, pdf), "Blob");
    }
    fz_catch(ctx) {
        fz_ignore_error(ctx);
        res = nullptr;
    }
    return res;
}

void PdfSidecarBlobXrefs(fz_context* ctx, pdf_document* pdf, Vec<int>& out) {
    if (!pdf) {
        return;
    }
    pdf_obj* blob = SidecarBlobObj(ctx, pdf);
    if (!blob) {
        return;
    }
    int num = 0;
    fz_var(num);
    fz_try(ctx) {
        if (pdf_is_indirect(ctx, blob)) {
            num = pdf_to_num(ctx, blob);
        }
    }
    fz_catch(ctx) {
        fz_ignore_error(ctx);
        num = 0;
    }
    if (num > 0) {
        out.Append(num);
    }
}

TempStr PdfSidecarDate(i64 millis) {
    SYSTEMTIME st{};
    if (millis > 0) {
        FILETIME ft{};
        u64 ticks = (u64)(millis * 10000) + 116444736000000000ULL;
        ft.dwLowDateTime = (DWORD)(ticks & 0xFFFFFFFF);
        ft.dwHighDateTime = (DWORD)(ticks >> 32);
        FILETIME local{};
        FileTimeToLocalFileTime(&ft, &local);
        FileTimeToSystemTime(&local, &st);
    } else {
        GetLocalTime(&st);
    }
    TIME_ZONE_INFORMATION tz{};
    DWORD kind = GetTimeZoneInformation(&tz);
    int bias = tz.Bias;
    if (kind == TIME_ZONE_ID_DAYLIGHT) {
        bias += tz.DaylightBias;
    } else if (kind == TIME_ZONE_ID_STANDARD) {
        bias += tz.StandardBias;
    }
    int offset = -bias;
    const char* sign = offset >= 0 ? "+" : "-";
    int minutes = offset < 0 ? -offset : offset;
    char buf[64];
    sprintf_s(buf, dimof(buf), "D:%04d%02d%02d%02d%02d%02d%s%02d'%02d'", st.wYear, st.wMonth, st.wDay, st.wHour,
              st.wMinute, st.wSecond, sign, minutes / 60, minutes % 60);
    return str::DupTemp(Str(buf));
}

static pdf_document* OpenPdf(fz_context* ctx, Str path) {
    pdf_document* pdf = nullptr;
    fz_var(pdf);
    fz_try(ctx) {
        pdf = pdf_open_document(ctx, CStrTemp(path));
    }
    fz_catch(ctx) {
        fz_ignore_error(ctx);
        pdf = nullptr;
    }
    return pdf;
}

bool PdfSidecarReadBlob(Str path, Vec<u8>& out) {
    if (!file::Exists(path)) {
        return false;
    }
    fz_context* ctx = fz_new_context(nullptr, nullptr, FZ_STORE_UNLIMITED);
    if (!ctx) {
        return false;
    }
    fz_register_document_handlers(ctx);
    pdf_document* pdf = OpenPdf(ctx, path);
    if (!pdf) {
        fz_drop_context(ctx);
        return false;
    }
    bool ok = false;
    pdf_obj* blob = SidecarBlobObj(ctx, pdf);
    if (blob) {
        fz_buffer* buf = nullptr;
        fz_var(buf);
        fz_try(ctx) {
            if (pdf_is_stream(ctx, blob)) {
                buf = pdf_load_raw_stream(ctx, blob);
            }
        }
        fz_catch(ctx) {
            fz_ignore_error(ctx);
            buf = nullptr;
        }
        if (buf) {
            u8* data = nullptr;
            size_t n = fz_buffer_storage(ctx, buf, &data);
            out.Reset();
            out.Append(data, (int)n);
            ok = n > 0;
            fz_drop_buffer(ctx, buf);
        }
    }
    pdf_drop_document(ctx, pdf);
    fz_drop_context(ctx);
    return ok;
}

bool PdfSidecarReadRecord(Str path, BookBlobRecord& out) {
    Vec<u8> blob;
    if (!PdfSidecarReadBlob(path, blob)) {
        return false;
    }
    return BookBlobDecode(blob.LendData(), blob.len, out);
}

TempStr PdfSidecarReadFingerprint(Str path) {
    if (!file::Exists(path)) {
        return {};
    }
    fz_context* ctx = fz_new_context(nullptr, nullptr, FZ_STORE_UNLIMITED);
    if (!ctx) {
        return {};
    }
    fz_register_document_handlers(ctx);
    pdf_document* pdf = OpenPdf(ctx, path);
    if (!pdf) {
        fz_drop_context(ctx);
        return {};
    }
    TempStr res = {};
    pdf_obj* mark = nullptr;
    fz_var(mark);
    fz_try(ctx) {
        mark = pdf_resolve_indirect(ctx, pdf_dict_gets(ctx, SidecarPrivate(ctx, pdf), "Fingerprint"));
    }
    fz_catch(ctx) {
        fz_ignore_error(ctx);
        mark = nullptr;
    }
    if (pdf_is_string(ctx, mark)) {
        const char* s = nullptr;
        fz_var(s);
        fz_try(ctx) {
            s = pdf_to_text_string(ctx, mark);
        }
        fz_catch(ctx) {
            fz_ignore_error(ctx);
            s = nullptr;
        }
        if (s && *s) {
            res = str::DupTemp(Str(s));
        }
    }
    if (!res.s) {
        char buf[512];
        TempStr key = str::JoinTemp("info:", Str(kInfoFingerprint));
        int n = -1;
        fz_var(n);
        fz_try(ctx) {
            n = pdf_lookup_metadata(ctx, pdf, key.s, buf, (int)dimof(buf));
        }
        fz_catch(ctx) {
            fz_ignore_error(ctx);
            n = -1;
        }
        if (n > 1) {
            res = str::DupTemp(Str(buf));
        }
    }
    pdf_drop_document(ctx, pdf);
    fz_drop_context(ctx);
    return res;
}

bool PdfSidecarHasBlob(Str path) {
    Vec<u8> blob;
    return PdfSidecarReadBlob(path, blob);
}

static pdf_obj* SidecarChild(fz_context* ctx, pdf_document* pdf, pdf_obj* parent, const char* name) {
    pdf_obj* found = pdf_resolve_indirect(ctx, pdf_dict_gets(ctx, parent, name));
    if (pdf_is_dict(ctx, found)) {
        return found;
    }
    pdf_obj* fresh = pdf_new_dict(ctx, pdf, 4);
    pdf_dict_puts_drop(ctx, parent, name, fresh);
    return pdf_resolve_indirect(ctx, pdf_dict_gets(ctx, parent, name));
}

static pdf_obj* SidecarInfo(fz_context* ctx, pdf_document* pdf) {
    pdf_obj* trailer = pdf_trailer(ctx, pdf);
    pdf_obj* found = pdf_resolve_indirect(ctx, pdf_dict_get(ctx, trailer, PDF_NAME(Info)));
    if (pdf_is_dict(ctx, found)) {
        return found;
    }
    pdf_obj* fresh = pdf_add_new_dict(ctx, pdf, 8);
    pdf_dict_put_drop(ctx, trailer, PDF_NAME(Info), fresh);
    return pdf_resolve_indirect(ctx, pdf_dict_get(ctx, trailer, PDF_NAME(Info)));
}

static void SetError(Str* errOut, Str msg) {
    if (errOut) {
        str::Free(*errOut);
        *errOut = str::Dup(msg);
    }
}

bool PdfSidecarWriteBlob(Str path, const u8* blob, int size, Str fingerprint, const Vec<PdfInfoField>* info,
                         Str* errOut) {
    if (!blob || size <= 0) {
        SetError(errOut, "refusing to embed an empty blob");
        return false;
    }
    if (!file::Exists(path)) {
        SetError(errOut, str::FormatTemp("%s is not a file", path));
        return false;
    }
    fz_context* ctx = fz_new_context(nullptr, nullptr, FZ_STORE_UNLIMITED);
    if (!ctx) {
        SetError(errOut, "no mupdf context");
        return false;
    }
    fz_register_document_handlers(ctx);
    pdf_document* pdf = OpenPdf(ctx, path);
    if (!pdf) {
        fz_drop_context(ctx);
        SetError(errOut, str::FormatTemp("%s is not a PDF", path));
        return false;
    }
    if (pdf_needs_password(ctx, pdf)) {
        pdf_drop_document(ctx, pdf);
        fz_drop_context(ctx);
        SetError(errOut, str::FormatTemp("%s is password protected", path));
        return false;
    }
    if (!pdf_can_be_saved_incrementally(ctx, pdf)) {
        pdf_drop_document(ctx, pdf);
        fz_drop_context(ctx);
        SetError(errOut, str::FormatTemp("%s cannot be saved incrementally", path));
        return false;
    }

    bool ok = false;
    fz_var(ok);
    fz_try(ctx) {
        fz_buffer* buf = fz_new_buffer_from_copied_data(ctx, blob, (size_t)size);
        pdf_obj* stream = nullptr;
        fz_try(ctx) {
            stream = pdf_add_stream(ctx, pdf, buf, nullptr, 0);
        }
        fz_always(ctx) {
            fz_drop_buffer(ctx, buf);
        }
        fz_catch(ctx) {
            fz_rethrow(ctx);
        }

        pdf_obj* root = SidecarCatalog(ctx, pdf);
        if (!root) {
            pdf_drop_obj(ctx, stream);
            fz_throw(ctx, FZ_ERROR_GENERIC, "document has no catalog");
        }
        pdf_obj* app = SidecarChild(ctx, pdf, SidecarChild(ctx, pdf, root, "PieceInfo"), kSidecarApp);
        pdf_dict_puts_drop(ctx, app, "LastModified", pdf_new_text_string(ctx, PdfSidecarDate().s));
        pdf_obj* hidden = SidecarChild(ctx, pdf, app, "Private");
        pdf_dict_puts_drop(ctx, hidden, "Version", pdf_new_int(ctx, kSidecarVersion));
        pdf_dict_puts_drop(ctx, hidden, "Blob", stream);
        if (fingerprint.len > 0) {
            pdf_dict_puts_drop(ctx, hidden, "Fingerprint", pdf_new_text_string(ctx, CStrTemp(fingerprint)));
        }

        bool anyInfo = fingerprint.len > 0;
        int nInfo = info ? info->len : 0;
        for (int i = 0; i < nInfo && !anyInfo; i++) {
            const PdfInfoField& f = (*info)[i];
            anyInfo = f.value && *f.value;
        }
        if (anyInfo) {
            pdf_obj* dict = SidecarInfo(ctx, pdf);
            for (int i = 0; i < nInfo; i++) {
                const PdfInfoField& f = (*info)[i];
                if (f.key && f.value && *f.value) {
                    pdf_dict_puts_drop(ctx, dict, f.key, pdf_new_text_string(ctx, f.value));
                }
            }
            if (fingerprint.len > 0) {
                pdf_dict_puts_drop(ctx, dict, kInfoFingerprint, pdf_new_text_string(ctx, CStrTemp(fingerprint)));
            }
        }

        pdf_write_options opts{};
        opts.permissions = ~0;
        opts.do_incremental = 1;
        pdf_save_document(ctx, pdf, CStrTemp(path), &opts);
        ok = true;
    }
    fz_catch(ctx) {
        SetError(errOut, Str(fz_caught_message(ctx)));
        fz_ignore_error(ctx);
        ok = false;
    }

    pdf_drop_document(ctx, pdf);
    fz_drop_context(ctx);
    return ok;
}
