JFLAGS = -g
JC = javac
JVM= java 
NODE= 5
SWITCH= 3
FILE= 

.SUFFIXES: .java .class

.java.class:
	$(JC) $(JFLAGS) $*.java

CLASSES = \
	Frame.java \
	FrameLostException.java \
	Main.java \
	Node.java \
	NodeThread.java \
	Switch.java \
	SwitchThread.java \
	CentralSwitch.java

MAIN = Main 


default: classes

classes: $(CLASSES:.java=.class)

full:
	make
	$(JVM) $(MAIN) $(NODE) $(SWITCH)
	
run:
	$(JVM) $(MAIN) $(NODE) $(SWITCH)

clean:
	$(RM) *.class
	$(RM) node*.txt
