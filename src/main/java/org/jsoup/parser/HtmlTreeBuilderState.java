package org.jsoup.parser;

import org.jsoup.helper.Validate;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.Range;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;

import static org.jsoup.internal.StringUtil.inSorted;
import static org.jsoup.parser.HtmlTreeBuilder.isSpecial;
import static org.jsoup.parser.HtmlTreeBuilderState.Constants.*;

/**
 * The Tree Builder's current state. Each state embodies the processing for the state, and transitions to other states.
 */
enum HtmlTreeBuilderState {
    Initial {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (isWhitespace(t)) {
                return true; // ignore whitespace until we get the first content
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment());
            } else if (t.isDoctype()) {
                // todo: parse error check on expected doctypes
                Token.Doctype d = t.asDoctype();
                DocumentType doctype = new DocumentType(
                    tb.settings.normalizeTag(d.getName()), d.getPublicIdentifier(), d.getSystemIdentifier());
                doctype.setPubSysKey(d.getPubSysKey());
                tb.getDocument().appendChild(doctype);
                tb.onNodeInserted(doctype);
                // todo: quirk state check on more doctype ids, if deemed useful (most are ancient legacy and presumably irrelevant)
                if (d.isForceQuirks() || !doctype.name().equals("html") || doctype.publicId().equalsIgnoreCase("HTML"))
                    tb.getDocument().quirksMode(Document.QuirksMode.quirks);
                tb.transition(BeforeHtml);
            } else {
                // todo: check not iframe srcdoc
                tb.getDocument().quirksMode(Document.QuirksMode.quirks); // missing doctype
                tb.transition(BeforeHtml);
                return tb.process(t); // re-process token
            }
            return true;
        }
    },
    BeforeHtml {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.isDoctype()) {
                tb.error(this);
                return false;
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment());
            } else if (isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter()); // out of spec - include whitespace
            } else if (t.isStartTag() && t.asStartTag().normalName().equals("html")) {
                tb.insertElementFor(t.asStartTag());
                tb.transition(BeforeHead);
            } else if (t.isEndTag() && (inSorted(t.asEndTag().normalName(), BeforeHtmlToHead))) {
                return anythingElse(t, tb);
            } else if (t.isEndTag()) {
                tb.error(this);
                return false;
            } else {
                return anythingElse(t, tb);
            }
            return true;
        }

        private boolean anythingElse(Token t, HtmlTreeBuilder tb) {
            tb.processStartTag("html");
            tb.transition(BeforeHead);
            return tb.process(t);
        }
    },
    BeforeHead {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter()); // out of spec - include whitespace
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment());
            } else if (t.isDoctype()) {
                tb.error(this);
                return false;
            } else if (t.isStartTag() && t.asStartTag().normalName().equals("html")) {
                return InBody.process(t, tb); // does not transition
            } else if (t.isStartTag() && t.asStartTag().normalName().equals("head")) {
                Element head = tb.insertElementFor(t.asStartTag());
                tb.setHeadElement(head);
                tb.transition(InHead);
            } else if (t.isEndTag() && (inSorted(t.asEndTag().normalName(), BeforeHtmlToHead))) {
                tb.processStartTag("head");
                return tb.process(t);
            } else if (t.isEndTag()) {
                tb.error(this);
                return false;
            } else {
                tb.processStartTag("head");
                return tb.process(t);
            }
            return true;
        }
    },
    InHead {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter()); // out of spec - include whitespace
                return true;
            }
            final String name;
            switch (t.type) {
                case Comment:
                    tb.insertCommentNode(t.asComment());
                    break;
                case Doctype:
                    tb.error(this);
                    return false;
                case StartTag:
                    Token.StartTag start = t.asStartTag();
                    name = start.normalName();
                    if (name.equals("html")) {
                        return InBody.process(t, tb);
                    } else if (inSorted(name, InHeadEmpty)) {
                        Element el = tb.insertEmptyElementFor(start);
                        // jsoup special: update base the first time it is seen
                        if (name.equals("base") && el.hasAttr("href"))
                            tb.maybeSetBaseUri(el);
                    } else if (name.equals("meta")) {
                        tb.insertEmptyElementFor(start);
                    } else if (name.equals("title")) {
                        HandleTextState(start, tb, tb.tagFor(start).textState());
                    } else if (inSorted(name, InHeadRaw)) {
                        HandleTextState(start, tb, tb.tagFor(start).textState());
                    } else if (name.equals("noscript")) {
                        // else if noscript && scripting flag = true: rawtext (jsoup doesn't run script, to handle as noscript)
                        tb.insertElementFor(start);
                        tb.transition(InHeadNoscript);
                    } else if (name.equals("script")) {
                        // skips some script rules as won't execute them
                        tb.tokeniser.transition(TokeniserState.ScriptData);
                        tb.markInsertionMode();
                        tb.transition(Text);
                        tb.insertElementFor(start);
                    } else if (name.equals("head")) {
                        tb.error(this);
                        return false;
                    } else if (name.equals("template")) {
                        tb.insertElementFor(start);
                        tb.insertMarkerToFormattingElements();
                        tb.framesetOk(false);
                        tb.transition(InTemplate);
                        tb.pushTemplateMode(InTemplate);
                    } else {
                        return anythingElse(t, tb);
                    }
                    break;
                case EndTag:
                    Token.EndTag end = t.asEndTag();
                    name = end.normalName();
                    if (name.equals("head")) {
                        tb.pop();
                        tb.transition(AfterHead);
                    } else if (inSorted(name, Constants.InHeadEnd)) {
                        return anythingElse(t, tb);
                    } else if (name.equals("template")) {
                        if (!tb.onStack(name)) {
                            tb.error(this);
                        } else {
                            tb.generateImpliedEndTags(true);
                            if (!tb.currentElementIs(name)) tb.error(this);
                            tb.popStackToClose(name);
                            tb.clearFormattingElementsToLastMarker();
                            tb.popTemplateMode();
                            tb.resetInsertionMode();
                        }
                    }
                    else {
                        tb.error(this);
                        return false;
                    }
                    break;
                default:
                    return anythingElse(t, tb);
            }
            return true;
        }

        private boolean anythingElse(Token t, TreeBuilder tb) {
            tb.processEndTag("head");
            return tb.process(t);
        }
    },
    InHeadNoscript {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.isDoctype()) {
                tb.error(this);
            } else if (t.isStartTag() && t.asStartTag().normalName().equals("html")) {
                return tb.process(t, InBody);
            } else if (t.isEndTag() && t.asEndTag().normalName().equals("noscript")) {
                tb.pop();
                tb.transition(InHead);
            } else if (isWhitespace(t) || t.isComment() || (t.isStartTag() && inSorted(t.asStartTag().normalName(),
                    InHeadNoScriptHead))) {
                return tb.process(t, InHead);
            } else if (t.isEndTag() && t.asEndTag().normalName().equals("br")) {
                return anythingElse(t, tb);
            } else if ((t.isStartTag() && inSorted(t.asStartTag().normalName(), InHeadNoscriptIgnore)) || t.isEndTag()) {
                tb.error(this);
                return false;
            } else {
                return anythingElse(t, tb);
            }
            return true;
        }

        private boolean anythingElse(Token t, HtmlTreeBuilder tb) {
            // note that this deviates from spec, which is to pop out of noscript and reprocess in head:
            // https://html.spec.whatwg.org/multipage/parsing.html#parsing-main-inheadnoscript
            // allows content to be inserted as data
            tb.error(this);
            tb.insertCharacterNode(new Token.Character().data(t.toString()));
            return true;
        }
    },
    AfterHead {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter());
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment());
            } else if (t.isDoctype()) {
                tb.error(this);
            } else if (t.isStartTag()) {
                Token.StartTag startTag = t.asStartTag();
                String name = startTag.normalName();
                if (name.equals("html")) {
                    return tb.process(t, InBody);
                } else if (name.equals("body")) {
                    tb.insertElementFor(startTag);
                    tb.framesetOk(false);
                    tb.transition(InBody);
                } else if (name.equals("frameset")) {
                    tb.insertElementFor(startTag);
                    tb.transition(InFrameset);
                } else if (inSorted(name, InBodyStartToHead)) {
                    tb.error(this);
                    Element head = tb.getHeadElement();
                    tb.push(head);
                    tb.process(t, InHead);
                    tb.removeFromStack(head);
                } else if (name.equals("head")) {
                    tb.error(this);
                    return false;
                } else {
                    anythingElse(t, tb);
                }
            } else if (t.isEndTag()) {
                String name = t.asEndTag().normalName();
                if (inSorted(name, AfterHeadBody)) {
                    anythingElse(t, tb);
                } else if (name.equals("template")) {
                    tb.process(t, InHead);
                }
                else {
                    tb.error(this);
                    return false;
                }
            } else {
                anythingElse(t, tb);
            }
            return true;
        }

        private boolean anythingElse(Token t, HtmlTreeBuilder tb) {
            tb.processStartTag("body");
            tb.framesetOk(true);
            return tb.process(t);
        }
    },
    InBody {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            switch (t.type) {
                case Character: {
                    Token.Character c = t.asCharacter();
                    if (c.getData().equals(nullString)) {
                        tb.error(this);
                        return false;
                    } else if (tb.framesetOk() && isWhitespace(c)) { // don't check if whitespace if frames already closed
                        tb.reconstructFormattingElements();
                        tb.insertCharacterNode(c);
                    } else {
                        tb.reconstructFormattingElements();
                        tb.insertCharacterNode(c);
                        tb.framesetOk(false);
                    }
                    break;
                }
                case Comment: {
                    tb.insertCommentNode(t.asComment());
                    break;
                }
                case Doctype: {
                    tb.error(this);
                    return false;
                }
                case StartTag:
                    return inBodyStartTag(t, tb);
                case EndTag:
                    return inBodyEndTag(t, tb);
                case EOF:
                    if (tb.templateModeSize() > 0)
                        return tb.process(t, InTemplate);
                    if (tb.onStackNot(InBodyEndOtherErrors))
                        tb.error(this);
                    // stop parsing
                    break;
                default:
                    Validate.wtf("Unexpected state: " + t.type); // XmlDecl only in XmlTreeBuilder
            }
            return true;
        }

        private boolean inBodyStartTag(Token t, HtmlTreeBuilder tb) {
            final Token.StartTag startTag = t.asStartTag();
            final String name = startTag.normalName();
            final ArrayList<Element> stack;
            Element el;

            switch (name) {
                case "a":
                    if (tb.getActiveFormattingElement("a") != null) {
                        tb.error(this);
                        tb.processEndTag("a");

                        // still on stack?
                        Element remainingA = tb.getFromStack("a");
                        if (remainingA != null) {
                            tb.removeFromActiveFormattingElements(remainingA);
                            tb.removeFromStack(remainingA);
                        }
                    }
                    tb.reconstructFormattingElements();
                    el = tb.insertElementFor(startTag);
                    tb.pushActiveFormattingElements(el);
                    break;
                case "span":
                    // same as final else, but short circuits lots of checks
                    tb.reconstructFormattingElements();
                    tb.insertElementFor(startTag);
                    break;
                case "li":
                    tb.framesetOk(false);
                    stack = tb.getStack();
                    for (int i = stack.size() - 1; i > 0; i--) {
                        el = stack.get(i);
                        if (el.nameIs("li")) {
                            tb.processEndTag("li");
                            break;
                        }
                        if (isSpecial(el) && !inSorted(el.normalName(), Constants.InBodyStartLiBreakers))
                            break;
                    }
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p");
                    }
                    tb.insertElementFor(startTag);
                    break;
                case "html":
                    tb.error(this);
                    if (tb.onStack("template")) return false; // ignore
                    // otherwise, merge attributes onto real html (if present)
                    stack = tb.getStack();
                    if (stack.size() > 0) {
                        Element html = tb.getStack().get(0);
                        mergeAttributes(startTag, html);
                    }
                    break;
                case "body":
                    tb.error(this);
                    stack = tb.getStack();
                    if (stack.size() == 1 || (stack.size() > 2 && !stack.get(1).nameIs("body")) || tb.onStack("template")) {
                        // only in fragment case
                        return false; // ignore
                    } else {
                        tb.framesetOk(false);
                        // will be on stack if this is a nested body. won't be if closed (which is a variance from spec, which leaves it on)
                        Element body = tb.getFromStack("body");
                        if (body != null) mergeAttributes(startTag, body);
                    }
                    break;
                case "frameset":
                    tb.error(this);
                    stack = tb.getStack();
                    if (stack.size() == 1 || (stack.size() > 2 && !stack.get(1).nameIs("body"))) {
                        // only in fragment case
                        return false; // ignore
                    } else if (!tb.framesetOk()) {
                        return false; // ignore frameset
                    } else {
                        Element second = stack.get(1);
                        if (second.parent() != null)
                            second.remove();
                        // pop up to html element
                        while (stack.size() > 1)
                            stack.remove(stack.size() - 1);
                        tb.insertElementFor(startTag);
                        tb.transition(InFrameset);
                    }
                    break;
                case "form":
                    if (tb.getFormElement() != null && !tb.onStack("template")) {
                        tb.error(this);
                        return false;
                    }
                    if (tb.inButtonScope("p")) {
                        tb.closeElement("p");
                    }
                    tb.insertFormElement(startTag, true, true); // won't associate to any template
                    break;
                case "plaintext":
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p");
                    }
                    tb.insertElementFor(startTag);
                    tb.tokeniser.transition(TokeniserState.PLAINTEXT); // once in, never gets out
                    break;
                case "button":
                    if (tb.inButtonScope("button")) {
                        // close and reprocess
                        tb.error(this);
                        tb.processEndTag("button");
                        tb.process(startTag);
                    } else {
                        tb.reconstructFormattingElements();
                        tb.insertElementFor(startTag);
                        tb.framesetOk(false);
                    }
                    break;
                case "nobr":
                    tb.reconstructFormattingElements();
                    if (tb.inScope("nobr")) {
                        tb.error(this);
                        tb.processEndTag("nobr");
                        tb.reconstructFormattingElements();
                    }
                    el = tb.insertElementFor(startTag);
                    tb.pushActiveFormattingElements(el);
                    break;
                case "table":
                    if (tb.getDocument().quirksMode() != Document.QuirksMode.quirks && tb.inButtonScope("p")) {
                        tb.processEndTag("p");
                    }
                    tb.insertElementFor(startTag);
                    tb.framesetOk(false);
                    tb.transition(InTable);
                    break;
                case "input":
                    tb.reconstructFormattingElements();
                    el = tb.insertEmptyElementFor(startTag);
                    if (!el.attr("type").equalsIgnoreCase("hidden"))
                        tb.framesetOk(false);
                    break;
                case "hr":
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p");
                    }
                    tb.insertEmptyElementFor(startTag);
                    tb.framesetOk(false);
                    break;
                case "image":
                    if (tb.getFromStack("svg") == null)
                        return tb.process(startTag.name("img")); // change <image> to <img>, unless in svg
                    else
                        tb.insertElementFor(startTag);
                    break;
                case "textarea":
                    tb.framesetOk(false);
                    HandleTextState(startTag, tb, tb.tagFor(startTag).textState());
                    break;
                case "xmp":
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p");
                    }
                    tb.reconstructFormattingElements();
                    tb.framesetOk(false);
                    HandleTextState(startTag, tb, tb.tagFor(startTag).textState());
                    break;
                case "iframe":
                    tb.framesetOk(false);
                    HandleTextState(startTag, tb, tb.tagFor(startTag).textState());
                    break;
                case "noembed":
                    // also handle noscript if script enabled
                    HandleTextState(startTag, tb, tb.tagFor(startTag).textState());
                    break;
                case "select":
                    tb.reconstructFormattingElements();
                    tb.insertElementFor(startTag);
                    tb.framesetOk(false);
                    if (startTag.selfClosing) break; // don't change states if not added to the stack

                    HtmlTreeBuilderState state = tb.state();
                    if (state.equals(InTable) || state.equals(InCaption) || state.equals(InTableBody) || state.equals(InRow) || state.equals(InCell))
                        tb.transition(InSelectInTable);
                    else
                        tb.transition(InSelect);
                    break;
                case "math":
                    tb.reconstructFormattingElements();
                    tb.insertForeignElementFor(startTag, Parser.NamespaceMathml);
                    break;
                case "svg":
                    tb.reconstructFormattingElements();
                    tb.insertForeignElementFor(startTag, Parser.NamespaceSvg);
                    break;
                // static final String[] Headings = new String[]{"h1", "h2", "h3", "h4", "h5", "h6"};
                case "h1":
                case "h2":
                case "h3":
                case "h4":
                case "h5":
                case "h6":
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p");
                    }
                    if (inSorted(tb.currentElement().normalName(), Constants.Headings)) {
                        tb.error(this);
                        tb.pop();
                    }
                    tb.insertElementFor(startTag);
                    break;
                // static final String[] InBodyStartPreListing = new String[]{"listing", "pre"};
                case "pre":
                case "listing":
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p");
                    }
                    tb.insertElementFor(startTag);
                    tb.reader.matchConsume("\n"); // ignore LF if next token
                    tb.framesetOk(false);
                    break;
                // static final String[] DdDt = new String[]{"dd", "dt"};
                case "dd":
                case "dt":
                    tb.framesetOk(false);
                    stack = tb.getStack();
                    final int bottom = stack.size() - 1;
                    final int upper = bottom >= MaxStackScan ? bottom - MaxStackScan : 0;
                    for (int i = bottom; i >= upper; i--) {
                        el = stack.get(i);
                        if (inSorted(el.normalName(), Constants.DdDt)) {
                            tb.processEndTag(el.normalName());
                            break;
                        }
                        if (isSpecial(el) && !inSorted(el.normalName(), Constants.InBodyStartLiBreakers))
                            break;
                    }
                    if (tb.inButtonScope("p")) {
                        tb.processEndTag("p");
                    }
                    tb.insertElementFor(startTag);
                    break;

                case "optgroup":
                case "option":
                    if (tb.currentElementIs("option"))
                        tb.processEndTag("option");
                    tb.reconstructFormattingElements();
                    tb.insertElementFor(startTag);
                    break;

                case "rb":
                case "rtc":
                    if (tb.inScope("ruby")) {
                        tb.generateImpliedEndTags();
                        if (!tb.currentElementIs("ruby"))
                            tb.error(this);
                    }
                    tb.insertElementFor(startTag);
                    break;

                case "rp":
                case "rt":
                    if (tb.inScope("ruby")) {
                        tb.generateImpliedEndTags("rtc");
                        if (!tb.currentElementIs("rtc") && !tb.currentElementIs("ruby"))
                            tb.error(this);
                    }
                    tb.insertElementFor(startTag);
                    break;

                // InBodyStartEmptyFormatters:
                case "area":
                case "br":
                case "embed":
                case "img":
                case "keygen":
                case "wbr":
                    tb.reconstructFormattingElements();
                    tb.insertEmptyElementFor(startTag);
                    tb.framesetOk(false);
                    break;
                // Formatters:
                case "b":
                case "big":
                case "code":
                case "em":
                case "font":
                case "i":
                case "s":
                case "small":
                case "strike":
                case "strong":
                case "tt":
                case "u":
                    tb.reconstructFormattingElements();
                    el = tb.insertElementFor(startTag);
                    tb.pushActiveFormattingElements(el);
                    break;
                default:
                    Tag tag = tb.tagFor(startTag);
                    TokeniserState textState = tag.textState();
                    if (textState != null) { // custom rcdata or rawtext (if we were in head, will have auto-transitioned here)
                        HandleTextState(startTag, tb, textState);
                    } else if (!tag.isKnownTag()) { // no other special rules for custom tags
                        tb.insertElementFor(startTag);
                    } else if (inSorted(name, Constants.InBodyStartPClosers)) {
                        if (tb.inButtonScope("p")) tb.processEndTag("p");
                        tb.insertElementFor(startTag);
                    } else if (inSorted(name, Constants.InBodyStartToHead)) {
                        return tb.process(t, InHead);
                    } else if (inSorted(name, Constants.InBodyStartApplets)) {
                        tb.reconstructFormattingElements();
                        tb.insertElementFor(startTag);
                        tb.insertMarkerToFormattingElements();
                        tb.framesetOk(false);
                    } else if (inSorted(name, Constants.InBodyStartMedia)) {
                        tb.insertEmptyElementFor(startTag);
                    } else if (inSorted(name, Constants.InBodyStartDrop)) {
                        tb.error(this);
                        return false;
                    } else {
                        tb.reconstructFormattingElements();
                        tb.insertElementFor(startTag);
                    }
            }
            return true;
        }
        private static final int MaxStackScan = 24; // used for DD / DT scan, prevents runaway

        private boolean inBodyEndTag(Token t, HtmlTreeBuilder tb) {
            final Token.EndTag endTag = t.asEndTag();
            final String name = endTag.normalName();

            switch (name) {
                case "template":
                    tb.process(t, InHead);
                    break;
                case "sarcasm": // *sigh*
                case "span":
                    // same as final fall through, but saves short circuit
                    return anyOtherEndTag(t, tb);
                case "li":
                    if (!tb.inListItemScope(name)) {
                        tb.error(this);
                        return false;
                    } else {
                        tb.generateImpliedEndTags(name);
                        if (!tb.currentElementIs(name))
                            tb.error(this);
                        tb.popStackToClose(name);
                    }
                    break;
                case "body":
                    if (!tb.inScope("body")) {
                        tb.error(this);
                        return false;
                    } else {
                        if (tb.onStackNot(InBodyEndOtherErrors))
                            tb.error(this);
                        tb.trackNodePosition(tb.getFromStack("body"), false); // track source position of close; body is left on stack, in case of trailers
                        tb.transition(AfterBody);
                    }
                    break;
                case "html":
                    if (!tb.onStack("body")) {
                        tb.error(this);
                        return false; // ignore
                    } else {
                        if (tb.onStackNot(InBodyEndOtherErrors))
                            tb.error(this);
                        tb.transition(AfterBody);
                        return tb.process(t); // re-process
                    }

                case "form":
                    if (!tb.onStack("template")) {
                        Element currentForm = tb.getFormElement();
                        tb.setFormElement(null);
                        if (currentForm == null || !tb.inScope(name)) {
                            tb.error(this);
                            return false;
                        }
                        tb.generateImpliedEndTags();
                        if (!tb.currentElementIs(name))
                            tb.error(this);
                        // remove currentForm from stack. will shift anything under up.
                        tb.removeFromStack(currentForm);
                    } else { // template on stack
                        if (!tb.inScope(name)) {
                            tb.error(this);
                            return false;
                        }
                        tb.generateImpliedEndTags();
                        if (!tb.currentElementIs(name)) tb.error(this);
                        tb.popStackToClose(name);
                    }
                    break;
                case "p":
                    if (!tb.inButtonScope(name)) {
                        tb.error(this);
                        tb.processStartTag(name); // if no p to close, creates an empty <p></p>
                        return tb.process(endTag);
                    } else {
                        tb.generateImpliedEndTags(name);
                        if (!tb.currentElementIs(name))
                            tb.error(this);
                        tb.popStackToClose(name);
                    }
                    break;
                case "dd":
                case "dt":
                    if (!tb.inScope(name)) {
                        tb.error(this);
                        return false;
                    } else {
                        tb.generateImpliedEndTags(name);
                        if (!tb.currentElementIs(name))
                            tb.error(this);
                        tb.popStackToClose(name);
                    }
                    break;
                case "h1":
                case "h2":
                case "h3":
                case "h4":
                case "h5":
                case "h6":
                    if (!tb.inScope(Constants.Headings)) {
                        tb.error(this);
                        return false;
                    } else {
                        tb.generateImpliedEndTags(name);
                        if (!tb.currentElementIs(name))
                            tb.error(this);
                        tb.popStackToClose(Constants.Headings);
                    }
                    break;
                case "br":
                    tb.error(this);
                    tb.processStartTag("br");
                    return false;
                default:
                    // todo - move rest to switch if desired
                    if (inSorted(name, Constants.InBodyEndAdoptionFormatters)) {
                        return inBodyEndTagAdoption(t, tb);
                    } else if (inSorted(name, Constants.InBodyEndClosers)) {
                        if (!tb.inScope(name)) {
                            // nothing to close
                            tb.error(this);
                            return false;
                        } else {
                            tb.generateImpliedEndTags();
                            if (!tb.currentElementIs(name))
                                tb.error(this);
                            tb.popStackToClose(name);
                        }
                    } else if (inSorted(name, Constants.InBodyStartApplets)) {
                        if (!tb.inScope("name")) {
                            if (!tb.inScope(name)) {
                                tb.error(this);
                                return false;
                            }
                            tb.generateImpliedEndTags();
                            if (!tb.currentElementIs(name))
                                tb.error(this);
                            tb.popStackToClose(name);
                            tb.clearFormattingElementsToLastMarker();
                        }
                    } else {
                        return anyOtherEndTag(t, tb);
                    }
            }
            return true;
        }

        boolean anyOtherEndTag(Token t, HtmlTreeBuilder tb) {
            final String name = t.asEndTag().normalName; // case insensitive search - goal is to preserve output case, not for the parse to be case sensitive
            final ArrayList<Element> stack = tb.getStack();

            // deviate from spec slightly to speed when super deeply nested
            Element elFromStack = tb.getFromStack(name);
            if (elFromStack == null) {
                tb.error(this);
                return false;
            }

            for (int pos = stack.size() - 1; pos >= 0; pos--) {
                Element node = stack.get(pos);
                if (node.nameIs(name)) {
                    tb.generateImpliedEndTags(name);
                    if (!tb.currentElementIs(name))
                        tb.error(this);
                    tb.popStackToClose(name);
                    break;
                } else {
                    if (isSpecial(node)) {
                        tb.error(this);
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean inBodyEndTagAdoption(Token t, HtmlTreeBuilder tb) {
            // https://html.spec.whatwg.org/multipage/parsing.html#adoption-agency-algorithm
            // JH: Including the spec notes here to simplify tracking / correcting. It's a bit gnarly and there may still be some nuances I haven't caught. But test cases and comparisons to browsers check out.

            // The adoption agency algorithm, which takes as its only argument a token token for which the algorithm is being run, consists of the following steps:
            final Token.EndTag endTag = t.asEndTag();
            final String subject = endTag.normalName; // 1. Let subject be token's tag name.

            // 2. If the [current node] is an [HTML element] whose tag name is subject, and the [current node] is not in the [list of active formatting elements], then pop the [current node] off the [stack of open elements] and return.
            if (tb.currentElement().normalName().equals(subject) && !tb.isInActiveFormattingElements(tb.currentElement())) {
                tb.pop();
                return true;
            }
            int outer = 0; // 3. Let outerLoopCounter be 0.
            while (true) { // 4. While true:
                if (outer >= 8) { // 1. If outerLoopCounter is greater than or equal to 8, then return.
                    return true;
                }
                outer++; // 2. Increment outerLoopCounter by 1.

                // 3. Let formattingElement be the last element in the [list of active formatting elements] that:
                //  - is between the end of the list and the last [marker] in the list, if any, or the start of the list otherwise, and
                //  - has the tag name subject.
                //  If there is no such element, then return and instead act as described in the "any other end tag" entry above.
                Element formatEl = null;
                for (int i = tb.formattingElements.size() - 1; i >= 0; i--) {
                    Element next = tb.formattingElements.get(i);
                    if (next == null) // marker
                        break;
                    if (next.normalName().equals(subject)) {
                        formatEl = next;
                        break;
                    }
                }
                if (formatEl == null) {
                    return anyOtherEndTag(t, tb);
                }

                // 4. If formattingElement is not in the [stack of open elements], then this is a [parse error]; remove the element from the list, and return.
                if (!tb.onStack(formatEl)) {
                    tb.error(this);
                    tb.removeFromActiveFormattingElements(formatEl);
                    return true;
                }

                //  5. If formattingElement is in the [stack of open elements], but the element is not [in scope], then this is a [parse error]; return.
                if (!tb.inScope(formatEl.normalName())) {
                    tb.error(this);
                    return false;
                } else if (tb.currentElement() != formatEl) { //  6. If formattingElement is not the [current node], this is a [parse error].
                    tb.error(this);
                }

                //  7. Let furthestBlock be the topmost node in the [stack of open elements] that is lower in the stack than formattingElement, and is an element in the [special]category. There might not be one.
                Element furthestBlock = null;
                ArrayList<Element> stack = tb.getStack();
                int fei = stack.lastIndexOf(formatEl);
                if (fei != -1) { // look down the stack
                    for (int i = fei + 1; i < stack.size(); i++) {
                        Element el = stack.get(i);
                        if (isSpecial(el)) {
                            furthestBlock = el;
                            break;
                        }
                    }
                }

                //  8. If there is no furthestBlock, then the UA must first pop all the nodes from the bottom of the [stack of open elements], from the [current node] up to and including formattingElement, then remove formattingElement from the [list of active formatting elements], and finally return.
                if (furthestBlock == null) {
                    while (tb.currentElement() != formatEl) {
                        tb.pop();
                    }
                    tb.pop();
                    tb.removeFromActiveFormattingElements(formatEl);
                    return true;
                }

                Element commonAncestor = tb.aboveOnStack(formatEl); // 9. Let commonAncestor be the element immediately above formattingElement in the [stack of open elements].
                if (commonAncestor == null) { tb.error(this); return true; } // Would be a WTF

                // 10. Let a bookmark note the position of formattingElement in the [list of active formatting elements] relative to the elements on either side of it in the list.
                // JH - I think this means its index? Or do we need a linked list?
                int bookmark = tb.positionOfElement(formatEl);

                Element el = furthestBlock; //  11. Let node and lastNode be furthestBlock.
                Element lastEl = furthestBlock;
                int inner = 0; // 12. Let innerLoopCounter be 0.

                while (true) { // 13. While true:
                    inner++; // 1. Increment innerLoopCounter by 1.
                    // 2. Let node be the element immediately above node in the [stack of open elements], or if node is no longer in the [stack of open elements] , the element that was immediately above node in the [stack of open elements] before node was removed.
                    if (!tb.onStack(el)) {
                        // if node was removed from stack, use the element that was above it
                        el = el.parent(); // JH - is there a situation where it's not the parent?
                    } else {
                        el = tb.aboveOnStack(el);
                    }
                    if (el == null || el.nameIs("body")) {
                        tb.error(this); // shouldn't be able to hit
                        break;
                    }
                    //  3. If node is formattingElement, then [break].
                    if (el == formatEl) {
                        break;
                    }

                    //  4. If innerLoopCounter is greater than 3 and node is in the [list of active formatting elements], then remove node from the [list of active formatting elements].
                    if (inner > 3 && tb.isInActiveFormattingElements(el)) {
                        tb.removeFromActiveFormattingElements(el);
                        break;
                    }
                    // 5. If node is not in the [list of active formatting elements], then remove node from the [stack of open elements] and [continue].
                    if (!tb.isInActiveFormattingElements(el)) {
                        tb.removeFromStack(el);
                        continue;
                    }

                    //  6. [Create an element for the token] for which the element node was created, in the [HTML namespace], with commonAncestor as the intended parent; replace the entry for node in the [list of active formatting elements] with an entry for the new element, replace the entry for node in the [stack of open elements] with an entry for the new element, and let node be the new element.
                    Element replacement = new Element(tb.tagFor(el.nodeName(), el.normalName(), tb.defaultNamespace(), ParseSettings.preserveCase), tb.getBaseUri());
                    tb.replaceActiveFormattingElement(el, replacement);
                    tb.replaceOnStack(el, replacement);
                    el = replacement;

                    //  7. If lastNode is furthestBlock, then move the aforementioned bookmark to be immediately after the new node in the [list of active formatting elements].
                    if (lastEl == furthestBlock) {
                        bookmark = tb.positionOfElement(el) + 1;
                    }
                    el.appendChild(lastEl); // 8. [Append] lastNode to node.
                    lastEl = el; // 9. Set lastNode to node.
                } // end inner loop # 13

                // 14. Insert whatever lastNode ended up being in the previous step at the [appropriate place for inserting a node], but using commonAncestor as the _override target_.
                // todo - impl https://html.spec.whatwg.org/multipage/parsing.html#appropriate-place-for-inserting-a-node fostering
                // just use commonAncestor as target:
                commonAncestor.appendChild(lastEl);
                // 15. [Create an element for the token] for which formattingElement was created, in the [HTML namespace], with furthestBlock as the intended parent.
                Element adoptor = new Element(formatEl.tag(), tb.getBaseUri());
                adoptor.attributes().addAll(formatEl.attributes()); // also attributes
                // 16. Take all of the child nodes of furthestBlock and append them to the element created in the last step.
                for (Node child : furthestBlock.childNodes()) {
                    adoptor.appendChild(child);
                }

                furthestBlock.appendChild(adoptor); // 17. Append that new element to furthestBlock.
                // 18. Remove formattingElement from the [list of active formatting elements], and insert the new element into the [list of active formatting elements] at the position of the aforementioned bookmark.
                tb.removeFromActiveFormattingElements(formatEl);
                tb.pushWithBookmark(adoptor, bookmark);
                // 19. Remove formattingElement from the [stack of open elements], and insert the new element into the [stack of open elements] immediately below the position of furthestBlock in that stack.
                tb.removeFromStack(formatEl);
                tb.insertOnStackAfter(furthestBlock, adoptor);
            } // end of outer loop # 4
        }
    },
    Text {
        // in script, style etc. normally treated as data tags
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.isCharacter()) {
                tb.insertCharacterNode(t.asCharacter());
            } else if (t.isEOF()) {
                tb.error(this);
                // if current node is script: already started
                tb.pop();
                tb.transition(tb.originalState());
                if (tb.state() == Text) // stack is such that we couldn't transition out; just close
                    tb.transition(InBody);
                return tb.process(t);
            } else if (t.isEndTag()) {
                // if: An end tag whose tag name is "script" -- scripting nesting level, if evaluating scripts
                tb.pop();
                tb.transition(tb.originalState());
            }
            return true;
        }
    },
    InTable {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.isCharacter() && inSorted(tb.currentElement().normalName(), InTableFoster)) {
                tb.resetPendingTableCharacters();
                tb.markInsertionMode();
                tb.transition(InTableText);
                return tb.process(t);
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment());
                return true;
            } else if (t.isDoctype()) {
                tb.error(this);
                return false;
            } else if (t.isStartTag()) {
                Token.StartTag startTag = t.asStartTag();
                String name = startTag.normalName();
                if (name.equals("caption")) {
                    tb.clearStackToTableContext();
                    tb.insertMarkerToFormattingElements();
                    tb.insertElementFor(startTag);
                    tb.transition(InCaption);
                } else if (name.equals("colgroup")) {
                    tb.clearStackToTableContext();
                    tb.insertElementFor(startTag);
                    tb.transition(InColumnGroup);
                } else if (name.equals("col")) {
                    tb.clearStackToTableContext();
                    tb.processStartTag("colgroup");
                    return tb.process(t);
                } else if (inSorted(name, InTableToBody)) {
                    tb.clearStackToTableContext();
                    tb.insertElementFor(startTag);
                    tb.transition(InTableBody);
                } else if (inSorted(name, InTableAddBody)) {
                    tb.clearStackToTableContext();
                    tb.processStartTag("tbody");
                    return tb.process(t);
                } else if (name.equals("table")) {
                    tb.error(this);
                    if (!tb.inTableScope(name)) { // ignore it
                        return false;
                    } else {
                        tb.popStackToClose(name);
                        if (!tb.resetInsertionMode()) {
                            // not per spec - but haven't transitioned out of table. so try something else
                            tb.insertElementFor(startTag);
                            return true;
                        }
                        return tb.process(t);
                    }
                } else if (inSorted(name, InTableToHead)) {
                    return tb.process(t, InHead);
                } else if (name.equals("input")) {
                    if (!(startTag.hasAttributes() && startTag.attributes.get("type").equalsIgnoreCase("hidden"))) {
                        return anythingElse(t, tb);
                    } else {
                        tb.insertEmptyElementFor(startTag);
                    }
                } else if (name.equals("form")) {
                    tb.error(this);
                    if (tb.getFormElement() != null || tb.onStack("template"))
                        return false;
                    else {
                        tb.insertFormElement(startTag, false, false); // not added to stack. can associate to template
                    }
                } else {
                    return anythingElse(t, tb);
                }
                return true; // todo: check if should return processed http://www.whatwg.org/specs/web-apps/current-work/multipage/tree-construction.html#parsing-main-intable
            } else if (t.isEndTag()) {
                Token.EndTag endTag = t.asEndTag();
                String name = endTag.normalName();

                if (name.equals("table")) {
                    if (!tb.inTableScope(name)) {
                        tb.error(this);
                        return false;
                    } else {
                        tb.popStackToClose("table");
                        tb.resetInsertionMode();
                    }
                } else if (inSorted(name, InTableEndErr)) {
                    tb.error(this);
                    return false;
                } else if (name.equals("template")) {
                    tb.process(t, InHead);
                } else {
                    return anythingElse(t, tb);
                }
                return true; // todo: as above todo
            } else if (t.isEOF()) {
                if (tb.currentElementIs("html"))
                    tb.error(this);
                return true; // stops parsing
            }
            return anythingElse(t, tb);
        }

        boolean anythingElse(Token t, HtmlTreeBuilder tb) {
            tb.error(this);
            tb.setFosterInserts(true);
            tb.process(t, InBody);
            tb.setFosterInserts(false);
            return true;
        }
    },
    InTableText {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.type == Token.TokenType.Character) {
                Token.Character c = t.asCharacter();
                if (c.getData().equals(nullString)) {
                    tb.error(this);
                    return false;
                } else {
                    tb.addPendingTableCharacters(c);
                }
            } else {
                // insert gathered table text into the correct element:
                if (tb.getPendingTableCharacters().size() > 0) {
                    final Token og = tb.currentToken; // update current token, so we can track cursor pos correctly
                    for (Token.Character c : tb.getPendingTableCharacters()) {
                        tb.currentToken = c;
                        if (!isWhitespace(c)) {
                            // InTable anything else section:
                            tb.error(this);
                            if (inSorted(tb.currentElement().normalName(), InTableFoster)) {
                                tb.setFosterInserts(true);
                                tb.process(c, InBody);
                                tb.setFosterInserts(false);
                            } else {
                                tb.process(c, InBody);
                            }
                        } else
                            tb.insertCharacterNode(c);
                    }
                    tb.currentToken = og;
                    tb.resetPendingTableCharacters();
                }
                tb.transition(tb.originalState());
                return tb.process(t);
            }
            return true;
        }
    },
    InCaption {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.isEndTag() && t.asEndTag().normalName().equals("caption")) {
                if (!tb.inTableScope("caption")) { // fragment case
                    tb.error(this);
                    return false;
                } else {
                    tb.generateImpliedEndTags();
                    if (!tb.currentElementIs("caption")) tb.error(this);
                    tb.popStackToClose("caption");
                    tb.clearFormattingElementsToLastMarker();
                    tb.transition(InTable);
                }
            } else if ((
                    t.isStartTag() && inSorted(t.asStartTag().normalName(), InCellCol) ||
                            t.isEndTag() && t.asEndTag().normalName().equals("table"))
                    ) {
                // same as above but processes after transition
                if (!tb.inTableScope("caption")) { // fragment case
                    tb.error(this);
                    return false;
                }
                tb.generateImpliedEndTags(false);
                if (!tb.currentElementIs("caption")) tb.error(this);
                tb.popStackToClose("caption");
                tb.clearFormattingElementsToLastMarker();
                tb.transition(InTable);
                InTable.process(t, tb); // doesn't check foreign context
            } else if (t.isEndTag() && inSorted(t.asEndTag().normalName(), InCaptionIgnore)) {
                tb.error(this);
                return false;
            } else {
                return tb.process(t, InBody);
            }
            return true;
        }
    },
    InColumnGroup {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter());
                return true;
            }
            switch (t.type) {
                case Comment:
                    tb.insertCommentNode(t.asComment());
                    break;
                case Doctype:
                    tb.error(this);
                    break;
                case StartTag:
                    Token.StartTag startTag = t.asStartTag();
                    switch (startTag.normalName()) {
                        case "html":
                            return tb.process(t, InBody);
                        case "col":
                            tb.insertEmptyElementFor(startTag);
                            break;
                        case "template":
                            tb.process(t, InHead);
                            break;
                        default:
                            return anythingElse(t, tb);
                    }
                    break;
                case EndTag:
                    Token.EndTag endTag = t.asEndTag();
                    String name = endTag.normalName();
                    switch (name) {
                        case "colgroup":
                            if (!tb.currentElementIs(name)) {
                                tb.error(this);
                                return false;
                            } else {
                                tb.pop();
                                tb.transition(InTable);
                            }
                            break;
                        case "template":
                            tb.process(t, InHead);
                            break;
                        default:
                            return anythingElse(t, tb);
                    }
                    break;
                case EOF:
                    if (tb.currentElementIs("html"))
                        return true; // stop parsing; frag case
                    else
                        return anythingElse(t, tb);
                default:
                    return anythingElse(t, tb);
            }
            return true;
        }

        private boolean anythingElse(Token t, HtmlTreeBuilder tb) {
            if (!tb.currentElementIs("colgroup")) {
                tb.error(this);
                return false;
            }
            tb.pop();
            tb.transition(InTable);
            tb.process(t);
            return true;
        }
    },
    InTableBody {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            final String name;

            switch (t.type) {
                case StartTag:
                    Token.StartTag startTag = t.asStartTag();
                    name = startTag.normalName();
                    if (name.equals("tr")) {
                        tb.clearStackToTableBodyContext();
                        tb.insertElementFor(startTag);
                        tb.transition(InRow);
                    } else if (inSorted(name, InCellNames)) {
                        tb.error(this);
                        tb.processStartTag("tr");
                        return tb.process(startTag);
                    } else if (inSorted(name, InTableBodyExit)) {
                        return exitTableBody(t, tb);
                    } else
                        return anythingElse(t, tb);
                    break;
                case EndTag:
                    Token.EndTag endTag = t.asEndTag();
                    name = endTag.normalName();
                    if (inSorted(name, InTableEndIgnore)) {
                        if (!tb.inTableScope(name)) {
                            tb.error(this);
                            return false;
                        } else {
                            tb.clearStackToTableBodyContext();
                            tb.pop();
                            tb.transition(InTable);
                        }
                    } else if (name.equals("table")) {
                        return exitTableBody(t, tb);
                    } else if (inSorted(name, InTableBodyEndIgnore)) {
                        tb.error(this);
                        return false;
                    } else
                        return anythingElse(t, tb);
                    break;
                default:
                    return anythingElse(t, tb);
            }
            return true;
        }

        private boolean exitTableBody(Token t, HtmlTreeBuilder tb) {
            if (!(tb.inTableScope("tbody") || tb.inTableScope("thead") || tb.inScope("tfoot"))) {
                // frag case
                tb.error(this);
                return false;
            }
            tb.clearStackToTableBodyContext();
            tb.processEndTag(tb.currentElement().normalName()); // tbody, tfoot, thead
            return tb.process(t);
        }

        private boolean anythingElse(Token t, HtmlTreeBuilder tb) {
            return tb.process(t, InTable);
        }
    },
    InRow {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.isStartTag()) {
                Token.StartTag startTag = t.asStartTag();
                String name = startTag.normalName();

                if (inSorted(name, InCellNames)) { // td, th
                    tb.clearStackToTableRowContext();
                    tb.insertElementFor(startTag);
                    tb.transition(InCell);
                    tb.insertMarkerToFormattingElements();
                } else if (inSorted(name, InRowMissing)) { // "caption", "col", "colgroup", "tbody", "tfoot", "thead", "tr"
                    if (!tb.inTableScope("tr")) {
                        tb.error(this);
                        return false;
                    }
                    tb.clearStackToTableRowContext();
                    tb.pop(); // tr
                    tb.transition(InTableBody);
                    return tb.process(t);
                } else {
                    return anythingElse(t, tb);
                }
            } else if (t.isEndTag()) {
                Token.EndTag endTag = t.asEndTag();
                String name = endTag.normalName();

                if (name.equals("tr")) {
                    if (!tb.inTableScope(name)) {
                        tb.error(this); // frag
                        return false;
                    }
                    tb.clearStackToTableRowContext();
                    tb.pop(); // tr
                    tb.transition(InTableBody);
                } else if (name.equals("table")) {
                    if (!tb.inTableScope("tr")) {
                        tb.error(this);
                        return false;
                    }
                    tb.clearStackToTableRowContext();
                    tb.pop(); // tr
                    tb.transition(InTableBody);
                    return tb.process(t);
                } else if (inSorted(name, InTableToBody)) { // "tbody", "tfoot", "thead"
                    if (!tb.inTableScope(name)) {
                        tb.error(this);
                        return false;
                    }
                    if (!tb.inTableScope("tr")) {
                        // not an error per spec?
                        return false;
                    }
                    tb.clearStackToTableRowContext();
                    tb.pop(); // tr
                    tb.transition(InTableBody);
                    return tb.process(t);
                } else if (inSorted(name, InRowIgnore)) {
                    tb.error(this);
                    return false;
                } else {
                    return anythingElse(t, tb);
                }
            } else {
                return anythingElse(t, tb);
            }
            return true;
        }

        private boolean anythingElse(Token t, HtmlTreeBuilder tb) {
            return tb.process(t, InTable);
        }
    },
    InCell {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.isEndTag()) {
                Token.EndTag endTag = t.asEndTag();
                String name = endTag.normalName();

                if (inSorted(name, Constants.InCellNames)) { // td, th
                    if (!tb.inTableScope(name)) {
                        tb.error(this);
                        tb.transition(InRow); // might not be in scope if empty: <td /> and processing fake end tag
                        return false;
                    }
                    tb.generateImpliedEndTags();
                    if (!tb.currentElementIs(name))
                        tb.error(this);
                    tb.popStackToClose(name);
                    tb.clearFormattingElementsToLastMarker();
                    tb.transition(InRow);
                } else if (inSorted(name, Constants.InCellBody)) {
                    tb.error(this);
                    return false;
                } else if (inSorted(name, Constants.InCellTable)) {
                    if (!tb.inTableScope(name)) {
                        tb.error(this);
                        return false;
                    }
                    closeCell(tb);
                    return tb.process(t);
                } else {
                    return anythingElse(t, tb);
                }
            } else if (t.isStartTag() &&
                    inSorted(t.asStartTag().normalName(), Constants.InCellCol)) {
                if (!(tb.inTableScope("td") || tb.inTableScope("th"))) {
                    tb.error(this);
                    return false;
                }
                closeCell(tb);
                return tb.process(t);
            } else {
                return anythingElse(t, tb);
            }
            return true;
        }

        private boolean anythingElse(Token t, HtmlTreeBuilder tb) {
            return tb.process(t, InBody);
        }

        private void closeCell(HtmlTreeBuilder tb) {
            if (tb.inTableScope("td"))
                tb.processEndTag("td");
            else
                tb.processEndTag("th"); // only here if th or td in scope
        }
    },
    InSelect {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            final String name;

            switch (t.type) {
                case Character:
                    Token.Character c = t.asCharacter();
                    if (c.getData().equals(nullString)) {
                        tb.error(this);
                        return false;
                    } else {
                        tb.insertCharacterNode(c);
                    }
                    break;
                case Comment:
                    tb.insertCommentNode(t.asComment());
                    break;
                case Doctype:
                    tb.error(this);
                    return false;
                case StartTag:
                    Token.StartTag start = t.asStartTag();
                    name = start.normalName();
                    if (name.equals("html"))
                        return tb.process(start, InBody);
                    else if (name.equals("option")) {
                        if (tb.currentElementIs("option"))
                            tb.processEndTag("option");
                        tb.insertElementFor(start);
                    } else if (name.equals("optgroup")) {
                        if (tb.currentElementIs("option"))
                            tb.processEndTag("option"); // pop option and flow to pop optgroup
                        if (tb.currentElementIs("optgroup"))
                            tb.processEndTag("optgroup");
                        tb.insertElementFor(start);
                    } else if (name.equals("select")) {
                        tb.error(this);
                        return tb.processEndTag("select");
                    } else if (inSorted(name, InSelectEnd)) {
                        tb.error(this);
                        if (!tb.inSelectScope("select"))
                            return false; // frag
                        tb.processEndTag("select");
                        return tb.process(start);
                    } else if (name.equals("script") || name.equals("template")) {
                        return tb.process(t, InHead);
                    } else {
                        return anythingElse(t, tb);
                    }
                    break;
                case EndTag:
                    Token.EndTag end = t.asEndTag();
                    name = end.normalName();
                    switch (name) {
                        case "optgroup":
                            if (tb.currentElementIs("option") && tb.aboveOnStack(tb.currentElement()) != null && tb.aboveOnStack(tb.currentElement()).nameIs("optgroup"))
                                tb.processEndTag("option");
                            if (tb.currentElementIs("optgroup"))
                                tb.pop();
                            else
                                tb.error(this);
                            break;
                        case "option":
                            if (tb.currentElementIs("option"))
                                tb.pop();
                            else
                                tb.error(this);
                            break;
                        case "select":
                            if (!tb.inSelectScope(name)) {
                                tb.error(this);
                                return false;
                            } else {
                                tb.popStackToClose(name);
                                tb.resetInsertionMode();
                            }
                            break;
                        case "template":
                            return tb.process(t, InHead);
                        default:
                            return anythingElse(t, tb);
                    }
                    break;
                case EOF:
                    if (!tb.currentElementIs("html"))
                        tb.error(this);
                    break;
                default:
                    return anythingElse(t, tb);
            }
            return true;
        }

        private boolean anythingElse(Token t, HtmlTreeBuilder tb) {
            tb.error(this);
            return false;
        }
    },
    InSelectInTable {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.isStartTag() && inSorted(t.asStartTag().normalName(), InSelectTableEnd)) {
                tb.error(this);
                tb.popStackToClose("select");
                tb.resetInsertionMode();
                return tb.process(t);
            } else if (t.isEndTag() && inSorted(t.asEndTag().normalName(), InSelectTableEnd)) {
                tb.error(this);
                if (tb.inTableScope(t.asEndTag().normalName())) {
                    tb.popStackToClose("select");
                    tb.resetInsertionMode();
                    return (tb.process(t));
                } else
                    return false;
            } else {
                return tb.process(t, InSelect);
            }
        }
    },
    InTemplate {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            final String name;
            switch (t.type) {
                case Character:
                case Comment:
                case Doctype:
                    tb.process(t, InBody);
                    break;
                case StartTag:
                    name = t.asStartTag().normalName();
                    if (inSorted(name, InTemplateToHead))
                        tb.process(t, InHead);
                    else if (inSorted(name, InTemplateToTable)) {
                        tb.popTemplateMode();
                        tb.pushTemplateMode(InTable);
                        tb.transition(InTable);
                        return tb.process(t);
                    }
                    else if (name.equals("col")) {
                        tb.popTemplateMode();
                        tb.pushTemplateMode(InColumnGroup);
                        tb.transition(InColumnGroup);
                        return tb.process(t);
                    } else if (name.equals("tr")) {
                        tb.popTemplateMode();
                        tb.pushTemplateMode(InTableBody);
                        tb.transition(InTableBody);
                        return tb.process(t);
                    } else if (name.equals("td") || name.equals("th")) {
                        tb.popTemplateMode();
                        tb.pushTemplateMode(InRow);
                        tb.transition(InRow);
                        return tb.process(t);
                    } else {
                        tb.popTemplateMode();
                        tb.pushTemplateMode(InBody);
                        tb.transition(InBody);
                        return tb.process(t);
                    }

                    break;
                case EndTag:
                    name = t.asEndTag().normalName();
                    if (name.equals("template"))
                        tb.process(t, InHead);
                    else {
                        tb.error(this);
                        return false;
                    }
                    break;
                case EOF:
                    if (!tb.onStack("template")) {// stop parsing
                        return true;
                    }
                    tb.error(this);
                    tb.popStackToClose("template");
                    tb.clearFormattingElementsToLastMarker();
                    tb.popTemplateMode();
                    tb.resetInsertionMode();
                    // spec deviation - if we did not break out of Template, stop processing, and don't worry about cleaning up ultra-deep template stacks
                    // limited depth because this can recurse and will blow stack if too deep
                    if (tb.state() != InTemplate && tb.templateModeSize() < 12)
                        return tb.process(t);
                    else return true;
                default:
                    Validate.wtf("Unexpected state: " + t.type); // XmlDecl only in XmlTreeBuilder
            }
            return true;
        }
    },
    AfterBody {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            Element html = tb.getFromStack("html");
            if (isWhitespace(t)) {
                // spec deviation - currently body is still on stack, but we want this to go to the html node
                if (html != null)
                    tb.insertCharacterToElement(t.asCharacter(), html);
                else
                    tb.process(t, InBody); // will get into body
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment()); // into html node
            } else if (t.isDoctype()) {
                tb.error(this);
                return false;
            } else if (t.isStartTag() && t.asStartTag().normalName().equals("html")) {
                return tb.process(t, InBody);
            } else if (t.isEndTag() && t.asEndTag().normalName().equals("html")) {
                if (tb.isFragmentParsing()) {
                    tb.error(this);
                    return false;
                } else {
                    if (html != null) tb.trackNodePosition(html, false); // track source position of close; html is left on stack, in case of trailers
                    tb.transition(AfterAfterBody);
                }
            } else if (t.isEOF()) {
                // chillax! we're done
            } else {
                tb.error(this);
                tb.resetBody();
                return tb.process(t);
            }
            return true;
        }
    },
    InFrameset {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter());
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment());
            } else if (t.isDoctype()) {
                tb.error(this);
                return false;
            } else if (t.isStartTag()) {
                Token.StartTag start = t.asStartTag();
                switch (start.normalName()) {
                    case "html":
                        return tb.process(start, InBody);
                    case "frameset":
                        tb.insertElementFor(start);
                        break;
                    case "frame":
                        tb.insertEmptyElementFor(start);
                        break;
                    case "noframes":
                        return tb.process(start, InHead);
                    default:
                        tb.error(this);
                        return false;
                }
            } else if (t.isEndTag() && t.asEndTag().normalName().equals("frameset")) {
                if (tb.currentElementIs("html")) { // frag
                    tb.error(this);
                    return false;
                } else {
                    tb.pop();
                    if (!tb.isFragmentParsing() && !tb.currentElementIs("frameset")) {
                        tb.transition(AfterFrameset);
                    }
                }
            } else if (t.isEOF()) {
                if (!tb.currentElementIs("html")) {
                    tb.error(this);
                    return true;
                }
            } else {
                tb.error(this);
                return false;
            }
            return true;
        }
    },
    AfterFrameset {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (isWhitespace(t)) {
                tb.insertCharacterNode(t.asCharacter());
            } else if (t.isComment()) {
                tb.insertCommentNode(t.asComment());
            } else if (t.isDoctype()) {
                tb.error(this);
                return false;
            } else if (t.isStartTag() && t.asStartTag().normalName().equals("html")) {
                return tb.process(t, InBody);
            } else if (t.isEndTag() && t.asEndTag().normalName().equals("html")) {
                tb.transition(AfterAfterFrameset);
            } else if (t.isStartTag() && t.asStartTag().normalName().equals("noframes")) {
                return tb.process(t, InHead);
            } else if (t.isEOF()) {
                // cool your heels, we're complete
            } else {
                tb.error(this);
                return false;
            }
            return true;
        }
    },
    AfterAfterBody {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.isComment()) {
                tb.insertCommentNode(t.asComment());
            } else if (t.isDoctype() || (t.isStartTag() && t.asStartTag().normalName().equals("html"))) {
                return tb.process(t, InBody);
            } else if (isWhitespace(t)) {
                // spec deviation - body and html still on stack, but want this space to go after </html>
                Element doc = tb.getDocument();
                tb.insertCharacterToElement(t.asCharacter(), doc);
            }else if (t.isEOF()) {
                // nice work chuck
            } else {
                tb.error(this);
                tb.resetBody();
                return tb.process(t);
            }
            return true;
        }
    },
    AfterAfterFrameset {
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            if (t.isComment()) {
                tb.insertCommentNode(t.asComment());
            } else if (t.isDoctype() || isWhitespace(t) || (t.isStartTag() && t.asStartTag().normalName().equals("html"))) {
                return tb.process(t, InBody);
            } else if (t.isEOF()) {
                // nice work chuck
            } else if (t.isStartTag() && t.asStartTag().normalName().equals("noframes")) {
                return tb.process(t, InHead);
            } else {
                tb.error(this);
                return false;
            }
            return true;
        }
    },
    ForeignContent {
        // https://html.spec.whatwg.org/multipage/parsing.html#parsing-main-inforeign
        @Override boolean process(Token t, HtmlTreeBuilder tb) {
            switch (t.type) {
                case Character:
                    Token.Character c = t.asCharacter();
                    if (c.getData().equals(nullString))
                        tb.error(this);
                    else if (HtmlTreeBuilderState.isWhitespace(c))
                        tb.insertCharacterNode(c);
                    else {
                        tb.insertCharacterNode(c);
                        tb.framesetOk(false);
                    }
                    break;
                case Comment:
                    tb.insertCommentNode(t.asComment());
                    break;
                case Doctype:
                    tb.error(this);
                    break;
                case StartTag:
                    Token.StartTag start = t.asStartTag();
                    if (StringUtil.in(start.normalName, InForeignToHtml))
                        return processAsHtml(t, tb);
                    if (start.normalName.equals("font") && (
                        start.hasAttributeIgnoreCase("color")
                            || start.hasAttributeIgnoreCase("face")
                            || start.hasAttributeIgnoreCase("size")))
                        return processAsHtml(t, tb);

                    // Any other start:
                    // (whatwg says to fix up tag name and attribute case per a table - we will preserve original case instead)
                    String namespace = tb.currentElement().tag().namespace();
                    tb.insertForeignElementFor(start, namespace);
                    // (self-closing handled in insert)
                    // if self-closing svg script -- level and execution elided

                    // seemingly not in spec, but as browser behavior, get into ScriptData state for svg script; and allow custom data tags
                    TokeniserState textState = tb.tagFor(start.tagName.value(), start.normalName, namespace, tb.settings).textState();
                    if (textState != null) {
                        if (start.normalName.equals("script"))
                            tb.tokeniser.transition(TokeniserState.ScriptData);
                        else
                            tb.tokeniser.transition(textState);
                    }

                    break;

                case EndTag:
                    Token.EndTag end = t.asEndTag();
                    if (end.normalName.equals("br") || end.normalName.equals("p"))
                        return processAsHtml(t, tb);
                    if (end.normalName.equals("script") && tb.currentElementIs("script", Parser.NamespaceSvg)) {
                        // script level and execution elided.
                        tb.pop();
                        return true;
                    }

                    // Any other end tag
                    ArrayList<Element> stack = tb.getStack();
                    if (stack.isEmpty())
                        Validate.wtf("Stack unexpectedly empty");
                    int i = stack.size() - 1;
                    Element el = stack.get(i);
                    if (!el.nameIs(end.normalName))
                        tb.error(this);
                    while (i != 0) {
                        if (el.nameIs(end.normalName)) {
                            tb.popStackToCloseAnyNamespace(el.normalName());
                            return true;
                        }
                        i--;
                        el = stack.get(i);
                        if (el.tag().namespace().equals(Parser.NamespaceHtml)) {
                            return processAsHtml(t, tb);
                        }
                    }
                    break;

                case EOF:
                    // won't come through here, but for completion:
                    break;
                default:
                    Validate.wtf("Unexpected state: " + t.type); // XmlDecl only in XmlTreeBuilder
            }
            return true;
        }

        boolean processAsHtml(Token t, HtmlTreeBuilder tb) {
            return tb.state().process(t, tb);
        }
    };

    private static void mergeAttributes(Token.StartTag source, Element dest) {
        if (!source.hasAttributes()) return;
        for (Attribute attr : source.attributes) { // only iterates public attributes
            Attributes destAttrs = dest.attributes();
            if (!destAttrs.hasKey(attr.getKey())) {
                Range.AttributeRange range = attr.sourceRange(); // need to grab range before its parent changes
                destAttrs.put(attr);
                if (source.trackSource) { // copy the attribute range
                    destAttrs.sourceRange(attr.getKey(), range);
                }
            }
        }
    }

    private static final String nullString = String.valueOf('\u0000');

    abstract boolean process(Token t, HtmlTreeBuilder tb);

    private static boolean isWhitespace(Token t) {
        if (t.isCharacter()) {
            String data = t.asCharacter().getData();
            return StringUtil.isBlank(data);
        }
        return false;
    }

    private static void HandleTextState(Token.StartTag startTag, HtmlTreeBuilder tb, @Nullable TokeniserState state) {
        if (state != null)
            tb.tokeniser.transition(state);
        tb.markInsertionMode();
        tb.transition(Text);
        tb.insertElementFor(startTag);
    }

    // lists of tags to search through
    static final class Constants {
        static final String[] InHeadEmpty = new String[]{"base", "basefont", "bgsound", "command", "link"};
        static final String[] InHeadRaw = new String[]{"noframes", "style"};
        static final String[] InHeadEnd = new String[]{"body", "br", "html"};
        static final String[] AfterHeadBody = new String[]{"body", "br", "html"};
        static final String[] BeforeHtmlToHead = new String[]{"body", "br", "head", "html", };
        static final String[] InHeadNoScriptHead = new String[]{"basefont", "bgsound", "link", "meta", "noframes", "style"};
        static final String[] InBodyStartToHead = new String[]{"base", "basefont", "bgsound", "command", "link", "meta", "noframes", "script", "style", "template", "title"};
        static final String[] InBodyStartPClosers = new String[]{"address", "article", "aside", "blockquote", "center", "details", "dir", "div", "dl",
            "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "menu", "nav", "ol",
            "p", "section", "summary", "ul"};
        static final String[] Headings = new String[]{"h1", "h2", "h3", "h4", "h5", "h6"};
        static final String[] InBodyStartLiBreakers = new String[]{"address", "div", "p"};
        static final String[] DdDt = new String[]{"dd", "dt"};
        static final String[] InBodyStartApplets = new String[]{"applet", "marquee", "object"};
        static final String[] InBodyStartMedia = new String[]{"param", "source", "track"};
        static final String[] InBodyStartInputAttribs = new String[]{"action", "name", "prompt"};
        static final String[] InBodyStartDrop = new String[]{"caption", "col", "colgroup", "frame", "head", "tbody", "td", "tfoot", "th", "thead", "tr"};
        static final String[] InBodyEndClosers = new String[]{"address", "article", "aside", "blockquote", "button", "center", "details", "dir", "div",
            "dl", "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "listing", "menu",
            "nav", "ol", "pre", "section", "summary", "ul"};
        static final String[] InBodyEndOtherErrors = new String[] {"body", "dd", "dt", "html", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc", "tbody", "td", "tfoot", "th", "thead", "tr"};
        static final String[] InBodyEndAdoptionFormatters = new String[]{"a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small", "strike", "strong", "tt", "u"};
        static final String[] InTableToBody = new String[]{"tbody", "tfoot", "thead"};
        static final String[] InTableAddBody = new String[]{"td", "th", "tr"};
        static final String[] InTableToHead = new String[]{"script", "style", "template"};
        static final String[] InCellNames = new String[]{"td", "th"};
        static final String[] InCellBody = new String[]{"body", "caption", "col", "colgroup", "html"};
        static final String[] InCellTable = new String[]{ "table", "tbody", "tfoot", "thead", "tr"};
        static final String[] InCellCol = new String[]{"caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr"};
        static final String[] InTableEndErr = new String[]{"body", "caption", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr"};
        static final String[] InTableFoster = new String[]{"table", "tbody", "tfoot", "thead", "tr"};
        static final String[] InTableBodyExit = new String[]{"caption", "col", "colgroup", "tbody", "tfoot", "thead"};
        static final String[] InTableBodyEndIgnore = new String[]{"body", "caption", "col", "colgroup", "html", "td", "th", "tr"};
        static final String[] InRowMissing = new String[]{"caption", "col", "colgroup", "tbody", "tfoot", "thead", "tr"};
        static final String[] InRowIgnore = new String[]{"body", "caption", "col", "colgroup", "html", "td", "th"};
        static final String[] InSelectEnd = new String[]{"input", "keygen", "textarea"};
        static final String[] InSelectTableEnd = new String[]{"caption", "table", "tbody", "td", "tfoot", "th", "thead", "tr"};
        static final String[] InTableEndIgnore = new String[]{"tbody", "tfoot", "thead"};
        static final String[] InHeadNoscriptIgnore = new String[]{"head", "noscript"};
        static final String[] InCaptionIgnore = new String[]{"body", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr"};
        static final String[] InTemplateToHead = new String[] {"base", "basefont", "bgsound", "link", "meta", "noframes", "script", "style", "template", "title"};
        static final String[] InTemplateToTable = new String[] {"caption", "colgroup", "tbody", "tfoot", "thead"};
        static final String[] InForeignToHtml = new String[] {"b", "big", "blockquote", "body", "br", "center", "code", "dd", "div", "dl", "dt", "em", "embed", "h1", "h2", "h3", "h4", "h5", "h6", "head", "hr", "i", "img", "li", "listing", "menu", "meta", "nobr", "ol", "p", "pre", "ruby", "s", "small", "span", "strike", "strong", "sub", "sup", "table", "tt", "u", "ul", "var"};
    }
}
