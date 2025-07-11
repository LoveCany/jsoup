package org.jsoup.select;

import org.jsoup.helper.Validate;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.LeafNode;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.PseudoTextElement;
import org.jsoup.nodes.TextNode;
import org.jsoup.nodes.XmlDeclaration;
import org.jsoup.parser.ParseSettings;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jsoup.internal.Normalizer.lowerCase;
import static org.jsoup.internal.Normalizer.normalize;
import static org.jsoup.internal.StringUtil.normaliseWhitespace;


/**
 An Evaluator tests if an element (or a node) meets the selector's requirements. Obtain an evaluator for a given CSS selector
 with {@link Selector#evaluatorOf(String css)}. If you are executing the same selector on many elements (or documents), it
 can be more efficient to compile and reuse an Evaluator than to reparse the selector on each invocation of select().
 <p>Evaluators are thread-safe and may be used concurrently across multiple documents.</p>
 */
public abstract class Evaluator {
    protected Evaluator() {
    }

    /**
     Provides a Predicate for this Evaluator, matching the test Element.
     * @param root the root Element, for match evaluation
     * @return a predicate that accepts an Element to test for matches with this Evaluator
     * @since 1.17.1
     */
    public Predicate<Element> asPredicate(Element root) {
        return element -> matches(root, element);
    }

    Predicate<Node> asNodePredicate(Element root) {
        return node -> matches(root, node);
    }

    /**
     * Test if the element meets the evaluator's requirements.
     *
     * @param root    Root of the matching subtree
     * @param element tested element
     * @return Returns <tt>true</tt> if the requirements are met or
     * <tt>false</tt> otherwise
     */
    public abstract boolean matches(Element root, Element element);

    final boolean matches(Element root, Node node) {
        if (node instanceof Element) {
            return matches(root, (Element) node);
        } else if (node instanceof LeafNode && wantsNodes()) {
            return matches(root, (LeafNode) node);
        }
        return false;
    }

    boolean matches(Element root, LeafNode leafNode) {
        return false;
    }

    boolean wantsNodes() {
        return false;
    }

    /**
     Reset any internal state in this Evaluator before executing a new Collector evaluation.
     */
    protected void reset() {
    }

    /**
     A relative evaluator cost function. During evaluation, Evaluators are sorted by ascending cost as an optimization.
     * @return the relative cost of this Evaluator
     */
    protected int cost() {
        return 5; // a nominal default cost
    }

    /**
     * Evaluator for tag name
     */
    public static final class Tag extends Evaluator {
        private final String tagName;

        public Tag(String tagName) {
            this.tagName = tagName;
        }

        @Override
        public boolean matches(Element root, Element element) {
            return (element.nameIs(tagName));
        }

        @Override protected int cost() {
            return 1;
        }

        @Override
        public String toString() {
            return String.format("%s", tagName);
        }
    }

    /**
     * Evaluator for tag name that starts with prefix; used for ns|*
     */
    public static final class TagStartsWith extends Evaluator {
        private final String tagName;

        public TagStartsWith(String tagName) {
            this.tagName = tagName;
        }

        @Override
        public boolean matches(Element root, Element element) {
            return (element.normalName().startsWith(tagName));
        }

        @Override
        public String toString() {
            return String.format("%s|*", tagName);
        }
    }


    /**
     * Evaluator for tag name that ends with suffix; used for *|el
     */
    public static final class TagEndsWith extends Evaluator {
        private final String tagName;

        public TagEndsWith(String tagName) {
            this.tagName = tagName;
        }

        @Override
        public boolean matches(Element root, Element element) {
            return (element.normalName().endsWith(tagName));
        }

        @Override
        public String toString() {
            return String.format("*|%s", tagName);
        }
    }

    /**
     * Evaluator for element id
     */
    public static final class Id extends Evaluator {
        private final String id;

        public Id(String id) {
            this.id = id;
        }

        @Override
        public boolean matches(Element root, Element element) {
            return (id.equals(element.id()));
        }

        @Override protected int cost() {
            return 2;
        }
        @Override
        public String toString() {
            return String.format("#%s", id);
        }
    }

    /**
     * Evaluator for element class
     */
    public static final class Class extends Evaluator {
        private final String className;

        public Class(String className) {
            this.className = className;
        }

        @Override
        public boolean matches(Element root, Element element) {
            return (element.hasClass(className));
        }

        @Override protected int cost() {
            return 8; // does whitespace scanning; more than .contains()
        }

        @Override
        public String toString() {
            return String.format(".%s", className);
        }

    }

    /**
     * Evaluator for attribute name matching
     */
    public static final class Attribute extends Evaluator {
        private final String key;

        public Attribute(String key) {
            this.key = key;
        }

        @Override
        public boolean matches(Element root, Element element) {
            return element.hasAttr(key);
        }

        @Override protected int cost() {
            return 2;
        }

        @Override
        public String toString() {
            return String.format("[%s]", key);
        }
    }

    /**
     * Evaluator for attribute name prefix matching
     */
    public static final class AttributeStarting extends Evaluator {
        private final String keyPrefix;

        public AttributeStarting(String keyPrefix) {
            Validate.notNull(keyPrefix); // OK to be empty - will find elements with any attributes
            this.keyPrefix = lowerCase(keyPrefix);
        }

        @Override
        public boolean matches(Element root, Element element) {
            List<org.jsoup.nodes.Attribute> values = element.attributes().asList();
            for (org.jsoup.nodes.Attribute attribute : values) {
                if (lowerCase(attribute.getKey()).startsWith(keyPrefix))
                    return true;
            }
            return false;
        }

        @Override protected int cost() {
            return 6;
        }

        @Override
        public String toString() {
            return String.format("[^%s]", keyPrefix);
        }

    }

    /**
     * Evaluator for attribute name/value matching
     */
    public static final class AttributeWithValue extends AttributeKeyPair {
        public AttributeWithValue(String key, String value) {
            super(key, value);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return element.hasAttr(key) && value.equalsIgnoreCase(element.attr(key).trim());
        }

        @Override protected int cost() {
            return 3;
        }

        @Override
        public String toString() {
            return String.format("[%s=%s]", key, value);
        }

    }

    /**
     * Evaluator for attribute name != value matching
     */
    public static final class AttributeWithValueNot extends AttributeKeyPair {
        public AttributeWithValueNot(String key, String value) {
            super(key, value);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return !value.equalsIgnoreCase(element.attr(key));
        }

        @Override protected int cost() {
            return 3;
        }

        @Override
        public String toString() {
            return String.format("[%s!=%s]", key, value);
        }

    }

    /**
     * Evaluator for attribute name/value matching (value prefix)
     */
    public static final class AttributeWithValueStarting extends AttributeKeyPair {
        public AttributeWithValueStarting(String key, String value) {
            super(key, value, false);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return element.hasAttr(key) && lowerCase(element.attr(key)).startsWith(value); // value is lower case already
        }

        @Override protected int cost() {
            return 4;
        }

        @Override
        public String toString() {
            return String.format("[%s^=%s]", key, value);
        }
    }

    /**
     * Evaluator for attribute name/value matching (value ending)
     */
    public static final class AttributeWithValueEnding extends AttributeKeyPair {
        public AttributeWithValueEnding(String key, String value) {
            super(key, value, false);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return element.hasAttr(key) && lowerCase(element.attr(key)).endsWith(value); // value is lower case
        }

        @Override protected int cost() {
            return 4;
        }

        @Override
        public String toString() {
            return String.format("[%s$=%s]", key, value);
        }
    }

    /**
     * Evaluator for attribute name/value matching (value containing)
     */
    public static final class AttributeWithValueContaining extends AttributeKeyPair {
        public AttributeWithValueContaining(String key, String value) {
            super(key, value);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return element.hasAttr(key) && lowerCase(element.attr(key)).contains(value); // value is lower case
        }

        @Override protected int cost() {
            return 6;
        }

        @Override
        public String toString() {
            return String.format("[%s*=%s]", key, value);
        }

    }

    /**
     * Evaluator for attribute name/value matching (value regex matching)
     */
    public static final class AttributeWithValueMatching extends Evaluator {
        final String key;
        final Pattern pattern;

        public AttributeWithValueMatching(String key, Pattern pattern) {
            this.key = normalize(key);
            this.pattern = pattern;
        }

        @Override
        public boolean matches(Element root, Element element) {
            return element.hasAttr(key) && pattern.matcher(element.attr(key)).find();
        }

        @Override protected int cost() {
            return 8;
        }

        @Override
        public String toString() {
            return String.format("[%s~=%s]", key, pattern.toString());
        }

    }

    /**
     * Abstract evaluator for attribute name/value matching
     */
    public abstract static class AttributeKeyPair extends Evaluator {
        final String key;
        final String value;

        public AttributeKeyPair(String key, String value) {
            this(key, value, true);
        }

        public AttributeKeyPair(String key, String value, boolean trimQuoted) {
            Validate.notEmpty(key);
            Validate.notEmpty(value);

            this.key = normalize(key);
            boolean quoted = value.startsWith("'") && value.endsWith("'")
                || value.startsWith("\"") && value.endsWith("\"");
            if (quoted)
                value = value.substring(1, value.length() - 1);

            // normalize value based on whether it was quoted and trimQuoted flag
            // keeps whitespace for attribute val starting or ending, when quoted
            if (trimQuoted || !quoted)
                this.value = normalize(value); // lowercase and trims
            else
                this.value = lowerCase(value); // only lowercase
        }
    }

    /**
     * Evaluator for any / all element matching
     */
    public static final class AllElements extends Evaluator {

        @Override
        public boolean matches(Element root, Element element) {
            return true;
        }

        @Override protected int cost() {
            return 10;
        }

        @Override
        public String toString() {
            return "*";
        }
    }

    /**
     * Evaluator for matching by sibling index number (e {@literal <} idx)
     */
    public static final class IndexLessThan extends IndexEvaluator {
        public IndexLessThan(int index) {
            super(index);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return root != element && element.elementSiblingIndex() < index;
        }

        @Override
        public String toString() {
            return String.format(":lt(%d)", index);
        }

    }

    /**
     * Evaluator for matching by sibling index number (e {@literal >} idx)
     */
    public static final class IndexGreaterThan extends IndexEvaluator {
        public IndexGreaterThan(int index) {
            super(index);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return element.elementSiblingIndex() > index;
        }

        @Override
        public String toString() {
            return String.format(":gt(%d)", index);
        }

    }

    /**
     * Evaluator for matching by sibling index number (e = idx)
     */
    public static final class IndexEquals extends IndexEvaluator {
        public IndexEquals(int index) {
            super(index);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return element.elementSiblingIndex() == index;
        }

        @Override
        public String toString() {
            return String.format(":eq(%d)", index);
        }

    }

    /**
     * Evaluator for matching the last sibling (css :last-child)
     */
    public static final class IsLastChild extends Evaluator {
		@Override
		public boolean matches(Element root, Element element) {
			final Element p = element.parent();
			return p != null && !(p instanceof Document) && element == p.lastElementChild();
		}

		@Override
		public String toString() {
			return ":last-child";
		}
    }

    public static final class IsFirstOfType extends IsNthOfType {
		public IsFirstOfType() {
			super(0,1);
		}
		@Override
		public String toString() {
			return ":first-of-type";
		}
    }

    public static final class IsLastOfType extends IsNthLastOfType {
		public IsLastOfType() {
			super(0,1);
		}
		@Override
		public String toString() {
			return ":last-of-type";
		}
    }


    public static abstract class CssNthEvaluator extends Evaluator {
        /** Step */
        protected final int a;
        /** Offset */
        protected final int b;

        public CssNthEvaluator(int step, int offset) {
            this.a = step;
            this.b = offset;
        }

        public CssNthEvaluator(int offset) {
            this(0, offset);
        }

        @Override
        public boolean matches(Element root, Element element) {
            final Element p = element.parent();
            if (p == null || (p instanceof Document)) return false;

            final int pos = calculatePosition(root, element);
            if (a == 0) return pos == b;

            return (pos - b) * a >= 0 && (pos - b) % a == 0;
        }

        @Override
        public String toString() {
            String format =
                (a == 0) ? ":%s(%3$d)"    // only offset (b)
                : (b == 0) ? ":%s(%2$dn)" // only step (a)
                : ":%s(%2$dn%3$+d)";      // step, offset
            return String.format(format, getPseudoClass(), a, b);
        }

        protected abstract String getPseudoClass();

        protected abstract int calculatePosition(Element root, Element element);
    }


    /**
     * css-compatible Evaluator for :eq (css :nth-child)
     *
     * @see IndexEquals
     */
    public static final class IsNthChild extends CssNthEvaluator {
        public IsNthChild(int step, int offset) {
            super(step, offset);
        }

        @Override
        protected int calculatePosition(Element root, Element element) {
            return element.elementSiblingIndex() + 1;
        }

        @Override
        protected String getPseudoClass() {
            return "nth-child";
        }
    }

    /**
     * css pseudo class :nth-last-child)
     *
     * @see IndexEquals
     */
    public static final class IsNthLastChild extends CssNthEvaluator {
        public IsNthLastChild(int step, int offset) {
            super(step, offset);
        }

        @Override
        protected int calculatePosition(Element root, Element element) {
    	    if (element.parent() == null) return 0;
        	return element.parent().childrenSize() - element.elementSiblingIndex();
        }

		@Override
		protected String getPseudoClass() {
			return "nth-last-child";
		}
    }

    /**
     * css pseudo class nth-of-type
     *
     */
    public static class IsNthOfType extends CssNthEvaluator {
        public IsNthOfType(int step, int offset) {
            super(step, offset);
        }

        @Override protected int calculatePosition(Element root, Element element) {
            Element parent = element.parent();
            if (parent == null)
                return 0;

            int pos = 0;
            final int size = parent.childNodeSize();
            for (int i = 0; i < size; i++) {
                Node node = parent.childNode(i);
                if (node.normalName().equals(element.normalName())) pos++;
                if (node == element) break;
            }
            return pos;
        }

        @Override
        protected String getPseudoClass() {
            return "nth-of-type";
        }
    }

    public static class IsNthLastOfType extends CssNthEvaluator {
        public IsNthLastOfType(int step, int offset) {
            super(step, offset);
        }

        @Override
        protected int calculatePosition(Element root, Element element) {
            Element parent = element.parent();
            if (parent == null)
                return 0;

            int pos = 0;
            Element next = element;
            while (next != null) {
                if (next.normalName().equals(element.normalName()))
                    pos++;
                next = next.nextElementSibling();
            }
            return pos;
        }

        @Override
        protected String getPseudoClass() {
            return "nth-last-of-type";
        }
    }

    /**
     * Evaluator for matching the first sibling (css :first-child)
     */
    public static final class IsFirstChild extends Evaluator {
    	@Override
    	public boolean matches(Element root, Element element) {
    		final Element p = element.parent();
    		return p != null && !(p instanceof Document) && element == p.firstElementChild();
    	}

    	@Override
    	public String toString() {
    		return ":first-child";
    	}
    }

    /**
     * css3 pseudo-class :root
     * @see <a href="http://www.w3.org/TR/selectors/#root-pseudo">:root selector</a>
     *
     */
    public static final class IsRoot extends Evaluator {
    	@Override
    	public boolean matches(Element root, Element element) {
    		final Element r = root instanceof Document ? root.firstElementChild() : root;
    		return element == r;
    	}

        @Override protected int cost() {
            return 1;
        }

    	@Override
    	public String toString() {
    		return ":root";
    	}
    }

    public static final class IsOnlyChild extends Evaluator {
		@Override
		public boolean matches(Element root, Element element) {
			final Element p = element.parent();
			return p!=null && !(p instanceof Document) && element.siblingElements().isEmpty();
		}
    	@Override
    	public String toString() {
    		return ":only-child";
    	}
    }

    public static final class IsOnlyOfType extends Evaluator {
		@Override
		public boolean matches(Element root, Element element) {
			final Element p = element.parent();
			if (p==null || p instanceof Document) return false;

			int pos = 0;
            Element next = p.firstElementChild();
            while (next != null) {
                if (next.normalName().equals(element.normalName()))
                    pos++;
                if (pos > 1)
                    break;
                next = next.nextElementSibling();
            }
        	return pos == 1;
		}
    	@Override
    	public String toString() {
    		return ":only-of-type";
    	}
    }

    public static final class IsEmpty extends Evaluator {
        @Override
        public boolean matches(Element root, Element el) {
            for (Node n = el.firstChild(); n != null; n = n.nextSibling()) {
                if (n instanceof TextNode) {
                    if (!((TextNode) n).isBlank())
                        return false; // non-blank text: not empty
                } else if (!(n instanceof Comment || n instanceof XmlDeclaration || n instanceof DocumentType))
                    return false; // non "blank" element: not empty
            }
            return true;
        }

        @Override
        public String toString() {
            return ":empty";
        }
    }

    /**
     * Abstract evaluator for sibling index matching
     *
     * @author ant
     */
    public abstract static class IndexEvaluator extends Evaluator {
        final int index;

        public IndexEvaluator(int index) {
            this.index = index;
        }
    }

    /**
     * Evaluator for matching Element (and its descendants) text
     */
    public static final class ContainsText extends Evaluator {
        private final String searchText;

        public ContainsText(String searchText) {
            this.searchText = lowerCase(normaliseWhitespace(searchText));
        }

        @Override
        public boolean matches(Element root, Element element) {
            return lowerCase(element.text()).contains(searchText);
        }

        @Override protected int cost() {
            return 10;
        }

        @Override
        public String toString() {
            return String.format(":contains(%s)", searchText);
        }
    }

    /**
     * Evaluator for matching Element (and its descendants) wholeText. Neither the input nor the element text is
     * normalized. <code>:containsWholeText()</code>
     * @since 1.15.1.
     */
    public static final class ContainsWholeText extends Evaluator {
        private final String searchText;

        public ContainsWholeText(String searchText) {
            this.searchText = searchText;
        }

        @Override
        public boolean matches(Element root, Element element) {
            return element.wholeText().contains(searchText);
        }

        @Override protected int cost() {
            return 10;
        }

        @Override
        public String toString() {
            return String.format(":containsWholeText(%s)", searchText);
        }
    }

    /**
     * Evaluator for matching Element (but <b>not</b> its descendants) wholeText. Neither the input nor the element text is
     * normalized. <code>:containsWholeOwnText()</code>
     * @since 1.15.1.
     */
    public static final class ContainsWholeOwnText extends Evaluator {
        private final String searchText;

        public ContainsWholeOwnText(String searchText) {
            this.searchText = searchText;
        }

        @Override
        public boolean matches(Element root, Element element) {
            return element.wholeOwnText().contains(searchText);
        }

        @Override
        public String toString() {
            return String.format(":containsWholeOwnText(%s)", searchText);
        }
    }

    /**
     * Evaluator for matching Element (and its descendants) data
     */
    public static final class ContainsData extends Evaluator {
        private final String searchText;

        public ContainsData(String searchText) {
            this.searchText = lowerCase(searchText);
        }

        @Override
        public boolean matches(Element root, Element element) {
            return lowerCase(element.data()).contains(searchText); // not whitespace normalized
        }

        @Override
        public String toString() {
            return String.format(":containsData(%s)", searchText);
        }
    }

    /**
     * Evaluator for matching Element's own text
     */
    public static final class ContainsOwnText extends Evaluator {
        private final String searchText;

        public ContainsOwnText(String searchText) {
            this.searchText = lowerCase(normaliseWhitespace(searchText));
        }

        @Override
        public boolean matches(Element root, Element element) {
            return lowerCase(element.ownText()).contains(searchText);
        }

        @Override
        public String toString() {
            return String.format(":containsOwn(%s)", searchText);
        }
    }

    /**
     * Evaluator for matching Element (and its descendants) text with regex
     */
    public static final class Matches extends Evaluator {
        private final Pattern pattern;

        public Matches(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean matches(Element root, Element element) {
            Matcher m = pattern.matcher(element.text());
            return m.find();
        }

        @Override protected int cost() {
            return 8;
        }

        @Override
        public String toString() {
            return String.format(":matches(%s)", pattern);
        }
    }

    /**
     * Evaluator for matching Element's own text with regex
     */
    public static final class MatchesOwn extends Evaluator {
        private final Pattern pattern;

        public MatchesOwn(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean matches(Element root, Element element) {
            Matcher m = pattern.matcher(element.ownText());
            return m.find();
        }

        @Override protected int cost() {
            return 7;
        }

        @Override
        public String toString() {
            return String.format(":matchesOwn(%s)", pattern);
        }
    }

    /**
     * Evaluator for matching Element (and its descendants) whole text with regex.
     * @since 1.15.1.
     */
    public static final class MatchesWholeText extends Evaluator {
        private final Pattern pattern;

        public MatchesWholeText(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean matches(Element root, Element element) {
            Matcher m = pattern.matcher(element.wholeText());
            return m.find();
        }

        @Override protected int cost() {
            return 8;
        }

        @Override
        public String toString() {
            return String.format(":matchesWholeText(%s)", pattern);
        }
    }

    /**
     * Evaluator for matching Element's own whole text with regex.
     * @since 1.15.1.
     */
    public static final class MatchesWholeOwnText extends Evaluator {
        private final Pattern pattern;

        public MatchesWholeOwnText(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean matches(Element root, Element element) {
            Matcher m = pattern.matcher(element.wholeOwnText());
            return m.find();
        }

        @Override protected int cost() {
            return 7;
        }

        @Override
        public String toString() {
            return String.format(":matchesWholeOwnText(%s)", pattern);
        }
    }

    /**
     @deprecated This selector is deprecated and will be removed in a future version. Migrate to <code>::textnode</code> using the <code>Element#selectNodes()</code> method instead.
     */
    @Deprecated
    public static final class MatchText extends Evaluator {
        private static boolean loggedError = false;

        public MatchText() {
            // log a deprecated error on first use; users typically won't directly construct this Evaluator and so won't otherwise get deprecation warnings
            if (!loggedError) {
                loggedError = true;
                System.err.println("WARNING: :matchText selector is deprecated and will be removed in a future version. Use Element#selectNodes(String, Class) with selector ::textnode and class TextNode instead.");
            }
        }

        @Override
        public boolean matches(Element root, Element element) {
            if (element instanceof PseudoTextElement)
                return true;

            List<TextNode> textNodes = element.textNodes();
            for (TextNode textNode : textNodes) {
                PseudoTextElement pel = new PseudoTextElement(
                    org.jsoup.parser.Tag.valueOf(element.tagName(), element.tag().namespace(), ParseSettings.preserveCase), element.baseUri(), element.attributes());
                textNode.replaceWith(pel);
                pel.appendChild(textNode);
            }
            return false;
        }

        @Override protected int cost() {
            return -1; // forces first evaluation, which prepares the DOM for later evaluator matches
        }

        @Override
        public String toString() {
            return ":matchText";
        }
    }
}
