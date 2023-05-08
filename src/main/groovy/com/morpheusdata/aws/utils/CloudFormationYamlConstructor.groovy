package com.morpheusdata.aws.utils

import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag;

public class CloudFormationYamlConstructor extends SafeConstructor {
	public CloudFormationYamlConstructor() {
		['And', 'Base64', 'Cidr', 'Equals', 'FindInMap', 'GetAZs', 'If', 'ImportValue', 'Join', 'Not', 'Or', 'Select', 'Split', 'Sub'].each { tag ->
			this.yamlConstructors.put(new Tag("!" + tag), new ConstructorCloudFormation(true, false))
		}
		this.yamlConstructors.put(new Tag("!Condition"), new ConstructorCloudFormation(false, false))
		this.yamlConstructors.put(new Tag("!Ref"), new ConstructorCloudFormation(false, false))
		this.yamlConstructors.put(new Tag("!GetAtt"), new ConstructorCloudFormation(true, true))
	}

	private class ConstructorCloudFormation extends AbstractConstruct {
		private requiresFnPrefix
		private splitScalar

		public ConstructorCloudFormation(requiresFnPrefix, splitScalar) {
			this.requiresFnPrefix = requiresFnPrefix
			this.splitScalar = splitScalar
		}

		public Object construct(Node node) {
			String key = node.getTag().getValue().substring(1)
			String prefix = requiresFnPrefix ? "Fn::" : ""
			def result = new HashMap<String, Object>()

			def nodeValue
			if (node instanceof ScalarNode) {
				Object val = (String) constructScalar((ScalarNode) node)
				if (splitScalar) {
					val = Arrays.asList(((String) val).split("\\."))
				}
				nodeValue = val;
			} else if (node instanceof SequenceNode) {
				nodeValue = constructSequence((SequenceNode) node)
			} else if (node instanceof MappingNode) {
				nodeValue = constructMapping((MappingNode) node)
			} else {
				throw new Exception("Unknown node type ${node}")
			}

			result.put(prefix + key, nodeValue)
			return result
		}
	}
}

