#include <tesseract/baseapi.h>
#include <leptonica/allheaders.h>
#include <windows.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>

static PIX* MakeTestImageGDI() {
    const char* lines[3] = {
        "OCR SMOKE TEST",
        "THE QUICK BROWN FOX",
        "JUMPS OVER THE LAZY DOG",
    };
    int W = 1600;
    int H = 600;

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

    int lineH = H / 3;
    for (int i = 0; i < 3; i++) {
        RECT lr{0, i * lineH, W, (i + 1) * lineH};
        DrawTextA(mem, lines[i], -1, &lr, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    }

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

    PIX* pix = pixCreate(W, H, 1);
    if (!pix) {
        free(pixels);
        return nullptr;
    }
    l_uint32* data = pixGetData(pix);
    int wpl = pixGetWpl(pix);
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            unsigned char* p = pixels + (size_t)y * rowBytes + (size_t)x * 4;
            unsigned char r = p[2];
            unsigned char g = p[1];
            unsigned char b = p[0];
            int lum = (r * 299 + g * 587 + b * 114) / 1000;
            if (lum < 128) {
                SET_DATA_BIT(data + y * wpl, x);
            } else {
                CLEAR_DATA_BIT(data + y * wpl, x);
            }
        }
    }
    free(pixels);
    return pix;
}

int main(int argc, char** argv) {
    const char* tessdata = "C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/build/ocr-install/share/tessdata/";
    if (argc >= 2) {
        tessdata = argv[1];
    }
    fprintf(stderr, "tessdata path: %s\n", tessdata);

    PIX* img = MakeTestImageGDI();
    if (!img) {
        fprintf(stderr, "pixCreate failed\n");
        return 1;
    }
    int W = pixGetWidth(img);
    int H = pixGetHeight(img);
    fprintf(stderr, "test image: %d x %d\n", W, H);

    tesseract::TessBaseAPI api;
    if (api.Init(tessdata, "eng")) {
        fprintf(stderr, "Tesseract init failed for path %s\n", tessdata);
        pixDestroy(&img);
        return 1;
    }
    fprintf(stderr, "Tesseract init succeeded\n");

    api.SetImage(img);
    fprintf(stderr, "SetImage succeeded\n");

    char* out = api.GetUTF8Text();
    if (out) {
        printf("OCR_RESULT_BEGIN\n");
        printf("%s", out);
        printf("OCR_RESULT_END\n");
        delete[] out;
    } else {
        printf("OCR_RESULT_BEGIN\nOCR_RESULT_END\n");
    }
    pixDestroy(&img);
    api.End();
    return 0;
}
