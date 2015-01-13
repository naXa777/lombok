package lombok.hookprocessor;

import static lombok.bytecode.AsmUtil.fixJSRInlining;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.tools.ant.taskdefs.Concat;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import lombok.bytecode.AsmUtil;
import lombok.bytecode.ClassFileMetaData;
import lombok.bytecode.PostCompilerApp;
import lombok.core.DiagnosticsReceiver;
import lombok.core.PostCompiler;

import com.zwitserloot.cmdreader.CmdReader;
import com.zwitserloot.cmdreader.Description;
import com.zwitserloot.cmdreader.InvalidCommandLineException;
import com.zwitserloot.cmdreader.Mandatory;
import com.zwitserloot.cmdreader.Sequential;
import com.zwitserloot.cmdreader.Shorthand;

public class HookPatcher {
	
	private static final String HOOK = "lombok/patcher/Hook";
	
	public static class CmdArgs {
		@Sequential
		@Mandatory
		@Description("paths to class files to be processed. If a directory is named, all files (recursively) in that directory will be processed.")
		private List<String> classFiles = new ArrayList<String>();
		
		@Shorthand("v")
		@Description("Prints lots of status information as the hook converter runs")
		boolean verbose = false;
		
		@Shorthand({"h", "?"})
		@Description("Shows this help text")
		boolean help = false;
	}
	
	public static void main(String[] args) throws Exception {
		System.exit(new HookPatcher().runApp(args));
	}
	
	public int runApp(String[] arguments) throws Exception {
		CmdReader<CmdArgs> reader = CmdReader.of(CmdArgs.class);
		CmdArgs args;
		try {
			args = reader.make(arguments);
			if (args.help) {
				System.out.println(reader.generateCommandLineHelp("java lombok.hookprocessor.HookPatcher"));
				return 0;
			}
		} catch (InvalidCommandLineException e) {
			System.err.println(e.getMessage());
			System.err.println(reader.generateCommandLineHelp("java lombok.hookprocessor.HookPatcher"));
			return 1;
		}
		
		List<File> filesToProcess = PostCompilerApp.cmdArgsToFiles(args.classFiles);
		int filesVisited = 0;
		int filesTouched = 0;
		for (File file : filesToProcess) {
			if (!file.exists() || !file.isFile()) {
				System.out.printf("Cannot find file '%s'\n", file.getAbsolutePath());
				continue;
			}
			filesVisited++;
			
			if (args.verbose) System.out.println("Processing " + file.getAbsolutePath());
			byte[] original = PostCompilerApp.readFile(file);
			if (!new ClassFileMetaData(original).usesMethod(HOOK, "<init>")) {
				continue;
			}
			byte[] clone = original.clone();
			byte[] transformed = applyTransformations(clone, file.toString(), DiagnosticsReceiver.CONSOLE);
			if (clone != transformed && transformed != null && !Arrays.equals(original, transformed)) {
				filesTouched++;
				if (args.verbose) System.out.println("Rewriting " + file.getAbsolutePath());
				PostCompilerApp.writeFile(file, transformed);
			}
		}
		
		if (args.verbose) {
			System.out.printf("Total files visited: %d total files changed: %d\n", filesVisited, filesTouched);
		}
		
		return filesVisited == 0 ? 1 : 0;
	}
	
	byte[] applyTransformations(byte[] original, String fileName, DiagnosticsReceiver reciever) {
		byte[] fixedByteCode = AsmUtil.fixJSRInlining(original);
		
		ClassReader reader = new ClassReader(fixedByteCode);
		ClassWriter writer = new ClassWriter(reader, 0);
		
		final AtomicBoolean changesMade = new AtomicBoolean();
		
		class HookReplacerVisitor extends MethodVisitor {
			private List<String> parameters = new ArrayList<String>();;
			private List<String> varargs = new ArrayList<String>();;
			
			HookReplacerVisitor(MethodVisitor mv) {
				super(Opcodes.ASM5, mv);
			}
			@Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
				boolean hit = true;
				if (hit && opcode != Opcodes.INVOKESPECIAL) hit = false;
				if (hit && !"<init>".equals(name)) hit = false;
				if (hit && !"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V".equals(desc)) hit = false;
				if (hit && !HOOK.equals(owner)) hit = false;
				if (hit) {
//					changesMade.set(true);
					System.out.println("parameters: " + parameters);
					System.out.println("varargs: " + varargs);
					System.out.println("################ this is the place!");
					super.visitMethodInsn(opcode, owner, name, desc, itf);
				} else {
					super.visitMethodInsn(opcode, owner, name, desc, itf);
				}
				clear();
			}
			
			void clear() {
				parameters.clear();
				varargs.clear();
			}
			
			@Override public void visitLdcInsn(Object arg0) {
				if (arg0 instanceof String) {
					parameters.add((String)arg0);
				} else {
					clear();
				}
				super.visitLdcInsn(arg0);
			}
			
			@Override public void visitInsn(int insn) {
				if (insn == Opcodes.AASTORE) {
					if (parameters.size() > 3) {
						varargs.add(parameters.remove(parameters.size() - 1));
					} else {
						clear();
					}
				}
				super.visitInsn(insn);
			}
			@Override public void visitTypeInsn(int insn, String type) {
				if (insn == Opcodes.ANEWARRAY) {
					if (type.equals("java/lang/String")) {
						if (parameters.size() < 3) {
							clear();
						} else {
							parameters.subList(0, parameters.size() - 3).clear();
						}
					} else {
						clear();
					}
				}
				super.visitTypeInsn(insn, type);
			}
		}
		
		reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
			@Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
				return new HookReplacerVisitor(super.visitMethod(access, name, desc, signature, exceptions));
			}
		}, 0);
		return changesMade.get() ? writer.toByteArray() : null;
	}
}
