extern "C" {
#include <mupdf/fitz.h>
}
#include <cstdio>
#include <cstring>
#include <cstdlib>

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: mupdf-ocr-smoke <pdf-file> [tessdata-path]\n");
        return 2;
    }
    const char* pdfPath = argv[1];
    const char* tessdata = "C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/out/dbg64/tessdata/";
    if (argc >= 3) {
        tessdata = argv[2];
    }
    fprintf(stderr, "pdf: %s\n", pdfPath);
    fprintf(stderr, "tessdata: %s\n", tessdata);

    fz_context* ctx = fz_new_context(nullptr, nullptr, FZ_STORE_UNLIMITED);
    if (!ctx) {
        fprintf(stderr, "fz_new_context failed\n");
        return 1;
    }
    fz_register_document_handlers(ctx);

    fz_document* doc = nullptr;
    fz_try(ctx) {
        doc = fz_open_document(ctx, pdfPath);
    }
    fz_catch(ctx) {
        fz_report_error(ctx);
        fz_drop_context(ctx);
        return 1;
    }
    if (!doc) {
        fz_drop_context(ctx);
        return 1;
    }

    int nPages = 0;
    fz_try(ctx) {
        nPages = fz_count_pages(ctx, doc);
    }
    fz_catch(ctx) {
        fz_report_error(ctx);
        nPages = 0;
    }
    fprintf(stderr, "pages: %d\n", nPages);
    if (nPages <= 0) {
        fz_drop_document(ctx, doc);
        fz_drop_context(ctx);
        return 1;
    }

    bool allOk = true;
    for (int pn = 0; pn < nPages; pn++) {
        fz_page* page = nullptr;
        fz_try(ctx) {
            page = fz_load_page(ctx, doc, pn);
        }
        fz_catch(ctx) {
            fz_report_error(ctx);
            page = nullptr;
        }
        if (!page) {
            allOk = false;
            continue;
        }
        fz_matrix ctm = fz_identity;
        fz_rect bounds = fz_bound_page(ctx, page);

        fz_stext_page* stext = fz_new_stext_page(ctx, bounds);
        fz_stext_options opts{};
        fz_parse_stext_options(ctx, &opts, "preserve-ligatures,preserve-whitespace,use-cid-for-unknown-unicode");
        fz_device* stext_dev = fz_new_stext_device(ctx, stext, &opts);

        fz_device* ocr_dev = nullptr;
        fz_try(ctx) {
            ocr_dev = fz_new_ocr_device(ctx, stext_dev, ctm, bounds, 0, "eng", tessdata, nullptr, nullptr);
        }
        fz_catch(ctx) {
            fz_report_error(ctx);
            ocr_dev = nullptr;
        }
        if (!ocr_dev) {
            fprintf(stderr, "page %d: fz_new_ocr_device failed\n", pn);
            fz_drop_device(ctx, stext_dev);
            fz_drop_stext_page(ctx, stext);
            fz_drop_page(ctx, page);
            allOk = false;
            continue;
        }
        fprintf(stderr, "page %d: OCR device created\n", pn);

        fz_try(ctx) {
            fz_run_page(ctx, page, ocr_dev, ctm, nullptr);
            fz_close_device(ctx, ocr_dev);
        }
        fz_catch(ctx) {
            fz_report_error(ctx);
        }
        fz_drop_device(ctx, ocr_dev);
        fz_close_device(ctx, stext_dev);
        fz_drop_device(ctx, stext_dev);

        printf("PAGE_%d_BEGIN\n", pn);
        bool gotText = false;
        for (fz_stext_block* block = stext->first_block; block; block = block->next) {
            if (block->type != FZ_STEXT_BLOCK_TEXT) continue;
            for (fz_stext_line* line = block->u.t.first_line; line; line = line->next) {
                int last = 0;
                for (fz_stext_char* ch = line->first_char; ch; ch = ch->next) {
                    if (ch->c >= 32 && ch->c < 127) {
                        printf("%c", ch->c);
                    } else {
                        printf("[%d]", ch->c);
                    }
                    last = ch->c;
                }
                if (last != '\n' && last > 0) {
                    printf("\n");
                }
                gotText = true;
            }
        }
        if (!gotText) {
            printf("(no structured text found)\n");
            allOk = false;
        }
        printf("PAGE_%d_END\n", pn);
        fz_drop_stext_page(ctx, stext);
        fz_drop_page(ctx, page);
    }
    fz_drop_document(ctx, doc);
    fz_drop_context(ctx);
    if (!allOk) return 1;
    return 0;
}
