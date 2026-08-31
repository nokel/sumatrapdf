extern "C" {
#include <mupdf/fitz.h>
}
#include <windows.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>

static void RenderText(int W, int H, const char* text, unsigned char* outPixels) {
    HDC screen = GetDC(nullptr);
    HDC mem = CreateCompatibleDC(screen);
    HBITMAP bmp = CreateCompatibleBitmap(screen, W, H);
    HFONT font = CreateFontW(-72, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE,
                             DEFAULT_CHARSET, OUT_OUTLINE_PRECIS, CLIP_DEFAULT_PRECIS,
                             CLEARTYPE_QUALITY, FF_SWISS, L"Arial");
    HGDIOBJ oldBmp = SelectObject(mem, bmp);
    HGDIOBJ oldFont = SelectObject(mem, font);

    RECT r{0, 0, W, H};
    HBRUSH bg = CreateSolidBrush(RGB(255, 255, 255));
    FillRect(mem, &r, bg);
    DeleteObject(bg);
    SetBkMode(mem, TRANSPARENT);
    SetTextColor(mem, RGB(0, 0, 0));
    DrawTextA(mem, text, -1, &r, DT_CENTER | DT_VCENTER | DT_SINGLELINE);

    BITMAPINFOHEADER bi{};
    bi.biSize = sizeof(bi);
    bi.biWidth = W;
    bi.biHeight = -H;
    bi.biPlanes = 1;
    bi.biBitCount = 32;
    bi.biCompression = BI_RGB;
    int rowBytes = W * 4;
    unsigned char* pixels = (unsigned char*)calloc((size_t)rowBytes * H, 1);
    GetDIBits(mem, bmp, 0, H, pixels, (BITMAPINFO*)&bi, DIB_RGB_COLORS);

    SelectObject(mem, oldBmp);
    SelectObject(mem, oldFont);
    DeleteObject(font);
    DeleteObject(bmp);
    DeleteDC(mem);
    ReleaseDC(nullptr, screen);

    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            unsigned char* p = pixels + (size_t)y * rowBytes + (size_t)x * 4;
            unsigned char r = p[2];
            unsigned char g = p[1];
            unsigned char b = p[0];
            int lum = (r * 299 + g * 587 + b * 114) / 1000;
            outPixels[y * W + x] = (unsigned char)lum;
        }
    }
    free(pixels);
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: make-test-pdf <output.pdf>\n");
        return 2;
    }
    const char* path = argv[1];

    const char* lines[3] = {
        "OCR SMOKE TEST",
        "THE QUICK BROWN FOX",
        "JUMPS OVER THE LAZY DOG",
    };
    int W = 1600;
    int H = 600;
    int lineH = H / 3;

    fz_context* ctx = fz_new_context(nullptr, nullptr, FZ_STORE_UNLIMITED);
    if (!ctx) return 1;
    fz_register_document_handlers(ctx);
    fz_set_pixmap_imagemask_resolution(ctx, 300);

    fz_document* doc = fz_new_pdf_document(ctx);
    fz_try(ctx) {
        for (int i = 0; i < 3; i++) {
            unsigned char* gray = (unsigned char*)calloc((size_t)W * lineH, 1);
            RenderText(W, lineH, lines[i], gray);

            fz_buffer* buf = fz_new_buffer_from_shared_data(ctx, gray, (size_t)W * lineH, [](fz_context*, void* ptr) { free(ptr); });
            fz_compressed_buffer* cb = fz_compress_image_buffer(ctx, buf, -1, FZ_IMAGE_FLATE);

            fz_image* img = fz_new_image_from_compressed_buffer(ctx, W, lineH, 8, 1, 1, cb, nullptr);
            fz_pixmap* pix = fz_new_pixmap_from_image(ctx, img, nullptr, nullptr);
            fz_image* png_img = fz_new_image_from_pixmap(ctx, pix, nullptr);
            fz_drop_pixmap(ctx, pix);
            fz_drop_image(ctx, img);

            fz_page* page = fz_new_page(ctx, doc, fz_make_rect(0, 0, W, H), 0, 0, FZ_RESOURCE_BLOCK_STRUCTURE);
            fz_device* dev = fz_new_draw_device(ctx, fz_identity, fz_identity);
            fz_matrix m = fz_concat(fz_scale((float)W / W, (float)H / lineH), fz_identity);
            fz_draw_image(ctx, dev, png_img, m, 1, 0);
            fz_close_device(ctx, dev);
            fz_drop_device(ctx, dev);
            fz_drop_page(ctx, page);
            fz_drop_image(ctx, png_img);
        }
        fz_save_document(ctx, doc, path, nullptr);
    }
    fz_catch(ctx) {
        fz_report_error(ctx);
        fz_drop_document(ctx, doc);
        fz_drop_context(ctx);
        return 1;
    }
    fz_drop_document(ctx, doc);
    fz_drop_context(ctx);
    fprintf(stderr, "Wrote %s\n", path);
    return 0;
}
