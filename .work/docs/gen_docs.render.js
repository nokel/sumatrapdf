// In-app manual renderer (issue #5712). Uses markdown-it 14.1.0, matching cmd/gen-docs.ts.
(function (global) {
  "use strict";

  const kCommandsSearchHtml = "<h3 style=\"margin-top: 1em;\">Find command:</h3>\r\n<table style=\"margin-bottom: 2em;\" class=\"collection-content\">\r\n  <thead>\r\n    <tr>\r\n      <th width=\"0\">Command IDs</th>\r\n      <th width=\"0\">Keyboard shortcuts</th>\r\n      <th width=\"0\">Command Palette</th>\r\n    </tr>\r\n  </thead>\r\n  <tbody>\r\n    <tr>\r\n      <td width=\"0\"><input type=\"text\" id=\"cmd_ids\" /></td>\r\n      <td width=\"0\"><input type=\"text\" id=\"key_sht\" /></td>\r\n      <td width=\"0\"><input type=\"text\" id=\"cmd_plt\" /></td>\r\n    </tr>\r\n  </tbody>\r\n</table>";
const kCommandsSearchJs = "function driver() {\r\n  let q =\r\n    \"//table[contains(@class,'collection-content')]/tbody/tr[not(./td/input)]\";\r\n  let rows = getElementByXpath(q);\r\n  // console.log(\"rows:\", rows.length);\r\n  let lists = [];\r\n  let inputs = [];\r\n  let selectors = [\"input#cmd_ids\", \"input#key_sht\", \"input#cmd_plt\"];\r\n  selectors.forEach((sel, idx) => {\r\n    let el = document.querySelector(sel);\r\n    inputs[idx] = el;\r\n    setEvent(el, tableFilter);\r\n  });\r\n  for (let i = 1; i <= selectors.length; i++) {\r\n    q =\r\n      \"//table[contains(@class,'collection-content')]/tbody/tr/td[(not(./input))][position()=\" +\r\n      i +\r\n      \"]\";\r\n    let els = getElementByXpath(q);\r\n    els = els.map((x) => x.innerText);\r\n    lists[i - 1] = els;\r\n  }\r\n  lists[1] = lists[1].map((x) => x.replace(/(?:(?<!\\+)|(?<=\\+\\+))\\,/g, \"\")); //removing commas b/w shortcuts\r\n\r\n  /**\r\n   * @param {HTMLElement} el\r\n   */\r\n  function hideEl(el) {\r\n    el.setAttribute(\"style\", \"display: none;\");\r\n  }\r\n\r\n  /**\r\n   * @param {HTMLElement} el\r\n   */\r\n  function showEl(el) {\r\n    el.removeAttribute(\"style\");\r\n  }\r\n\r\n  /**\r\n   * @param {HTMLElement} el\r\n   */\r\n  function isVisible(el) {\r\n    return !el.hasAttribute(\"style\");\r\n  }\r\n\r\n  // called when any of the 3 search input fields changes\r\n  // hides tr rows that don't match search query\r\n  function tableFilter() {\r\n    let regexs = [\r\n      getRegex_cmdids(inputs[0]),\r\n      getRegex_keysht(inputs[1]),\r\n      getRegex_cmdplt(inputs[2]),\r\n    ];\r\n    rows.forEach(hideEl);\r\n    let shortlist = new Array(rows.length).fill(undefined);\r\n    regexs.forEach((regex, list_index) => {\r\n      if (!!regex)\r\n        lists[list_index].forEach((item, row_index) => {\r\n          if (shortlist[row_index] === undefined)\r\n            shortlist[row_index] = regex.test(item);\r\n          else if (shortlist[row_index])\r\n            shortlist[row_index] = regex.test(item);\r\n        });\r\n    });\r\n    if (!regexs.some((x) => !!x)) {\r\n      rows.forEach(showEl);\r\n    } else {\r\n      shortlist.forEach((flag, index) => {\r\n        if (flag) {\r\n          showEl(rows[index]);\r\n        }\r\n      });\r\n    }\r\n\r\n    let qTables = \"//table[contains(@class,'collection-content')]\";\r\n    let tables = getElementByXpath(qTables);\r\n    // console.log(\"tables:\", tables);\r\n    for (let table of tables) {\r\n      let h = table.previousSibling;\r\n      if (h.nodeName == \"#text\") {\r\n        h = h.previousSibling;\r\n      }\r\n      let isPrevHdr = h.nodeName === \"H2\" || h.nodeName === \"H3\";\r\n      if (!isPrevHdr) {\r\n        console.log(\"h.nodeName is not header:\", h.nodeName);\r\n        continue;\r\n      }\r\n      let rows = table.querySelectorAll(\"tbody > tr\");\r\n      let nVisible = 0;\r\n      for (let row of rows) {\r\n        if (isVisible(row)) {\r\n          nVisible++;\r\n        }\r\n      }\r\n      if (nVisible > 0) {\r\n        showEl(table);\r\n        showEl(h);\r\n      } else {\r\n        hideEl(table);\r\n        hideEl(h);\r\n      }\r\n    }\r\n  }\r\n}\r\n\r\nfunction setEvent(target, callback) {\r\n  target.addEventListener(\"keyup\", callback);\r\n}\r\n\r\nfunction getElementByXpath(xpathToExecute) {\r\n  let result = [];\r\n  let snapshotNodes = document.evaluate(\r\n    xpathToExecute,\r\n    document,\r\n    null,\r\n    XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,\r\n    null\r\n  );\r\n  for (let i = 0; i < snapshotNodes.snapshotLength; i++)\r\n    result.push(snapshotNodes.snapshotItem(i));\r\n  return result;\r\n}\r\n\r\nfunction getRegex_cmdids(ele) {\r\n  let ip_val = ele.value.replace(/([^\\w\\s])/g, \"\").replace(/\\s+$/, \"\");\r\n  if (ip_val.length == 0) return false;\r\n  return new RegExp(ip_val.replace(/\\s+(\\w+)/g, \"(?=.*$1)\"), \"i\");\r\n}\r\n\r\nfunction getRegex_keysht(ele) {\r\n  let ip_val = ele.value.replace(/\\s+$/, \"\");\r\n  if (ip_val.length == 0) return false;\r\n  return new RegExp(\r\n    \"(?:(?=\\\\W)(?<=\\\\w)|(?<!\\\\w))(\" +\r\n      ip_val\r\n        .replace(/([^\\w\\s])/g, \"\\\\$1\")\r\n        .replace(/([^\\s]+)/g, \"($1)\")\r\n        .replace(/\\s+/g, \"|\") +\r\n      \")(?:(?<=\\\\W)(?=\\\\w)|(?!\\\\w))\",\r\n    \"i\"\r\n  );\r\n}\r\n\r\nfunction getRegex_cmdplt(ele) {\r\n  let ip_val = ele.value.replace(/\\s+$/, \"\");\r\n  if (ip_val.length == 0) return false;\r\n  return new RegExp(\r\n    \"(?:(?=\\\\W)(?<=\\\\w)|(?<!\\\\w))\" +\r\n      ip_val\r\n        .replace(/([^\\w\\s])/g, \"\\\\$1\")\r\n        .replace(/\\s+([\\w\\W]+)/g, \"(?=.*\\\\b$1)\"),\r\n    \"i\"\r\n  );\r\n}\r\ndriver();\r\n";


  const h1BreadcrumbsStart =
    '<div class="breadcrumbs"><div><a href="SumatraPDF-documentation.html">SumatraPDF documentation</a></div><div>/</div><div>';
  const h1BreadcrumbsEnd = "</div></div>";

  let manifest = null;
  let mainDocText = null;

  function removeNotionId(s) {
    if (s.length <= 32) return s;
    if (/^[0-9a-fA-F]{32}$/.test(s.slice(-32))) return s.slice(0, -32);
    return s;
  }

  function getHTMLFileName(mdName) {
    const name = mdName.split("#")[0];
    const base = name.replace(/\.md$/i, "");
    return removeNotionId(base).trim().replace(/ /g, "-") + ".html";
  }

  function htmlFileFromLocation() {
    const path = global.location.pathname || "";
    const base = path.split("/").pop() || "SumatraPDF-documentation.html";
    return base.split("#")[0].split("?")[0];
  }

  function slugify(text) {
    return text
      .toLowerCase()
      .replace(/[^\w -]/g, "")
      .replace(/ /g, "-");
  }

  function stripMiscDocsSection(text) {
    const startMarker = "## Misc docs";
    const endMarker = "## Downloads";
    const startIdx = text.indexOf(startMarker);
    if (startIdx < 0) return text;
    const endIdx = text.indexOf(endMarker, startIdx);
    if (endIdx < 0) return text;
    return text.slice(0, startIdx) + text.slice(endIdx);
  }

  function preProcess(text) {
    const lines = text.split("\n");
    let inCols = false;
    return lines
      .map(function (line) {
        if (line.trim() === ":columns") {
          if (!inCols) {
            inCols = true;
            return '\n<div class="doc-columns">\n';
          }
          inCols = false;
          return "\n</div>\n";
        }
        return line;
      })
      .join("\n");
  }

  function parseCsv(text) {
    const lines = text.trim().split("\n");
    return lines.map(function (line) {
      const fields = [];
      let cur = "";
      let inQ = false;
      for (let i = 0; i < line.length; i++) {
        const ch = line[i];
        if (inQ) {
          if (ch === '"' && line[i + 1] === '"') {
            cur += '"';
            i++;
          } else if (ch === '"') {
            inQ = false;
          } else {
            cur += ch;
          }
        } else if (ch === '"') {
          inQ = true;
        } else if (ch === ",") {
          fields.push(cur);
          cur = "";
        } else {
          cur += ch;
        }
      }
      fields.push(cur);
      return fields;
    });
  }

  function genCsvTableHTML(records) {
    if (!records.length) return "";
    const out = ['<table class="collection-content">'];
    const hdr = records[0];
    out.push("<thead>", "<tr>");
    for (let i = 0; i < hdr.length; i++) out.push("<th>" + hdr[i] + "</th>");
    out.push("</tr>", "</thead>", "<tbody>");
    for (let r = 1; r < records.length; r++) {
      out.push("<tr>");
      for (let i = 0; i < records[r].length; i++) {
        const cell = records[r][i].trim();
        if (!cell) {
          out.push("<td>", "</td>");
          continue;
        }
        out.push("<td>");
        out.push(i <= 1 ? "<code>" + cell + "</code>" : cell);
        out.push("</td>");
      }
      out.push("</tr>");
    }
    out.push("</tbody>", "</table>");
    return out.join("\n");
  }

  function isMultiLineCode(content) {
    return content.replace(/\r\n/g, "\n").trimEnd().includes("\n");
  }

  function genPlainCodeBlockHTML(codeInnerHtml, codeClass) {
    const cls = codeClass ? ' class="' + codeClass + '"' : "";
    return "<pre><code" + cls + ">" + codeInnerHtml + "</code></pre>\n";
  }

  function genCodeBlockHTML(codeInnerHtml, codeClass) {
    const cls = codeClass ? ' class="' + codeClass + '"' : "";
    return (
      '<div class="code-block">' +
      '<button type="button" class="sum-code-copy-btn" title="Copy to clipboard">Copy</button>' +
      "<pre><code" +
      cls +
      ">" +
      codeInnerHtml +
      "</code></pre>" +
      "</div>\n"
    );
  }

  function renderFenceCodeBlock(content, codeInnerHtml, codeClass) {
    if (!isMultiLineCode(content)) {
      return genPlainCodeBlockHTML(codeInnerHtml, codeClass);
    }
    return genCodeBlockHTML(codeInnerHtml, codeClass);
  }

  function getInlineText(token) {
    if (!token.children) return token.content || "";
    return token.children
      .map(function (t) {
        return t.content || "";
      })
      .join("");
  }

  function buildTocHTML(currentHtml) {
    if (!mainDocText) return "";
    const linkRe = /\[([^\]]+)\]\(([^)]+\.md)\)/g;
    const items = [];
    const lines = mainDocText.split("\n");
    let inColumns = false;
    for (let li = 0; li < lines.length; li++) {
      const line = lines[li];
      if (line.trim() === ":columns") {
        inColumns = !inColumns;
        continue;
      }
      if (!inColumns) continue;
      let match;
      while ((match = linkRe.exec(line)) !== null) {
        const title = match[1];
        const href = getHTMLFileName(match[2]);
        const cls = href === currentHtml ? ' class="toc-current"' : "";
        items.push("<a" + cls + ' href="' + href + '">' + title + "</a>");
      }
    }
    const searchHint =
      '<div onclick="window.openSearchDialog()" class="search-trigger-2"><kbd>Ctrl + K</kbd> to search...</div>\n';
    return (
      '<nav class="sidebar-toc">\n' +
      searchHint +
      '<div class="toc-title"></div>\n' +
      items.join("\n") +
      "\n</nav>"
    );
  }

  function createMarkdownRenderer(md) {
    md.renderer.rules.paragraph_open = function () {
      return "<div>";
    };
    md.renderer.rules.paragraph_close = function () {
      return "</div>\n";
    };

    md.renderer.rules.fence = function (tokens, idx) {
      const t = tokens[idx];
      const lang = t.info.trim().split(/\s+/)[0];
      if (lang === "commands") return genCsvTableHTML(parseCsv(t.content));
      return renderFenceCodeBlock(t.content, md.utils.escapeHtml(t.content));
    };

    md.renderer.rules.heading_open = function (tokens, idx) {
      const tok = tokens[idx];
      const text = getInlineText(tokens[idx + 1]);
      const id = slugify(text);
      return "<" + tok.tag + ' id="' + id + '">';
    };

    md.renderer.rules.heading_close = function (tokens, idx) {
      const tok = tokens[idx];
      const text = getInlineText(tokens[idx - 1]);
      const id = slugify(text);
      return '<a class="hlink" href="#' + id + '"> # </a></' + tok.tag + ">\n";
    };

    md.renderer.rules.link_open = function (tokens, idx, options, env, self) {
      const tok = tokens[idx];
      let href = tok.attrGet("href") || "";

      const isAbsolute =
        href.startsWith("https://") ||
        href.startsWith("http://") ||
        href.startsWith("mailto:");

      if (!isAbsolute) {
        const decoded = href.replace(/%20/g, " ");
        const hashIdx = decoded.indexOf("#");
        const fileName = hashIdx >= 0 ? decoded.slice(0, hashIdx) : decoded;
        const hash = hashIdx >= 0 ? decoded.slice(hashIdx + 1) : "";
        const ext = fileName.slice(fileName.lastIndexOf(".")).toLowerCase();
        if (ext === ".md") {
          if (fileName === "SumatraPDF-all-docs-for-llm-ai.md") {
            tok.attrSet(
              "href",
              "https://www.sumatrapdfreader.org/docs/SumatraPDF-all-docs-for-llm-ai.md",
            );
          } else {
            let dest = getHTMLFileName(fileName);
            if (hash) dest += "#" + hash;
            tok.attrSet("href", dest);
          }
        }
      }

      // Open non-internal links (any absolute http/https/mailto URL, including
      // ones we just rewrote to a sumatrapdfreader.org URL) in a new tab. The
      // in-app webview turns these into new-window requests and hands them to
      // the default OS browser instead of navigating the manual.
      const finalHref = tok.attrGet("href") || "";
      const isNonInternal =
        finalHref.startsWith("https://") ||
        finalHref.startsWith("http://") ||
        finalHref.startsWith("mailto:");
      if (isNonInternal) {
        tok.attrSet("target", "_blank");
        tok.attrSet("rel", "noopener noreferrer");
      }
      return self.renderToken(tokens, idx, options);
    };
  }

  function renderMarkdown(mdName, text) {
    const isMainPage = mdName === "SumatraPDF-documentation.md";
    if (isMainPage) {
      text = stripMiscDocsSection(text);
    }

    let h1Text = "";
    const h1Match = text.match(/^# (.+)$/m);
    if (h1Match) {
      h1Text = h1Match[1];
      text = text.replace(/^# .+\n?/, "");
    }

    text = preProcess(text);

    const md = global.markdownit({ html: true, typographer: true });
    createMarkdownRenderer(md);
    let innerHTML = md.render(text);

    if (h1Text && !isMainPage) {
      const bc = h1BreadcrumbsStart + h1Text + h1BreadcrumbsEnd;
      innerHTML = bc + innerHTML + '<div>&nbsp;</div>' + bc;
    }

    innerHTML = '<div class="notion-page">' + innerHTML + "</div>";
    if (mdName === "Commands.md") {
      innerHTML = replaceCommandsSearchPlaceholder(innerHTML);
    }
    return { innerHTML: innerHTML, h1Text: h1Text, isMainPage: isMainPage };
  }

  function getCommandsSearchHtml() {
    if (typeof kCommandsSearchHtml === "string" && kCommandsSearchHtml) {
      return kCommandsSearchHtml;
    }
    return null;
  }

  function replaceCommandsSearchPlaceholder(html) {
    const searchHtml = getCommandsSearchHtml();
    if (!searchHtml) {
      return html;
    }
    if (html.indexOf("<div>:search:</div>") >= 0) {
      return html.replace("<div>:search:</div>", searchHtml);
    }
    return html;
  }

  function fetchText(url) {
    return fetch(url).then(function (r) {
      if (!r.ok) throw new Error("failed to load " + url);
      return r.text();
    });
  }

  function ensureManifest() {
    if (manifest) return Promise.resolve(manifest);
    return fetchText("manifest.json").then(function (text) {
      manifest = JSON.parse(text);
      return manifest;
    });
  }

  function ensureMainDocText() {
    if (mainDocText) return Promise.resolve(mainDocText);
    return fetchText("SumatraPDF-documentation.md").then(function (text) {
      mainDocText = stripMiscDocsSection(text);
      return mainDocText;
    });
  }

  function runCommandsSearchScript(searchJs) {
    const script = document.createElement("script");
    script.textContent = searchJs;
    document.body.appendChild(script);
  }

  function injectCommandsSearch(innerSlot) {
    function runJs() {
      const js =
        typeof kCommandsSearchJs === "string" && kCommandsSearchJs
          ? kCommandsSearchJs
          : null;
      if (js) {
        runCommandsSearchScript(js);
        return Promise.resolve();
      }
      return fetchText("gen_docs.search.js").then(runCommandsSearchScript);
    }

    if (getCommandsSearchHtml() || !innerSlot) {
      return runJs();
    }
    return fetchText("gen_docs.search.html")
      .then(function (searchHtml) {
        innerSlot.innerHTML = innerSlot.innerHTML.replace(
          "<div>:search:</div>",
          searchHtml,
        );
      })
      .then(runJs);
  }

  function renderPage(mdName, currentHtml) {
    return Promise.all([ensureManifest(), ensureMainDocText()])
      .then(function () {
        return fetchText(mdName);
      })
      .then(function (text) {
        const rendered = renderMarkdown(mdName, text);
        const tocSlot = document.getElementById("toc-slot");
        const innerSlot = document.getElementById("inner-slot");
        const titleEl = document.getElementById("doc-title");
        if (tocSlot) {
          tocSlot.innerHTML = buildTocHTML(currentHtml);
        }
        if (innerSlot) {
          innerSlot.innerHTML = rendered.innerHTML;
        }
        if (typeof window.rebuildPageToc === "function") {
          window.rebuildPageToc();
        }
        if (titleEl) {
          const title = currentHtml.replace(".html", "").replace(/-/g, " ");
          titleEl.textContent = title;
        }
        if (mdName === "Commands.md") {
          return injectCommandsSearch(innerSlot);
        }
      });
  }

  function bootstrap() {
    if (typeof global.markdownit !== "function") {
      const innerSlot = document.getElementById("inner-slot");
      if (innerSlot) {
        innerSlot.textContent = "Documentation renderer failed to load.";
      }
      return;
    }

    const currentHtml = htmlFileFromLocation();
    ensureManifest()
      .then(function (m) {
        const mdName = m[currentHtml];
        if (!mdName) {
          throw new Error("unknown page " + currentHtml);
        }
        return renderPage(mdName, currentHtml);
      })
      .catch(function (err) {
        const innerSlot = document.getElementById("inner-slot");
        if (innerSlot) {
          innerSlot.textContent = String(err);
        }
      });
  }

  global.ManualDocs = {
    bootstrap: bootstrap,
    renderPage: renderPage,
    getHTMLFileName: getHTMLFileName,
    renderMarkdown: renderMarkdown,
  };
})(typeof window !== "undefined" ? window : globalThis);